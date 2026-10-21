#!/usr/bin/env python3
"""OPS-052 static checks on the Terraform source (no cloud account or plan needed; conftest covers the plan in CI).

  python3 infra/terraform/check-terraform.py            # check the repo
  python3 infra/terraform/check-terraform.py --selftest # prove each rule fails on a bad sample

Rules:
  tags      every taggable resource sets tags = merge(var.tags|local.tags, { ... data_class = ... })
  creds     no static cloud credentials in infra/terraform or the infra workflow (CI must use OIDC)
  regions   the reusable module hard-codes no region names (only modules/cell/residency.tf lists them)
  safety    Postgres private, encrypted, 35-day PITR, deletion protection; every bucket has a full public-access block;
            Valkey (OPS-020) TLS required, AUTH, encrypted at rest, a replica in a second AZ, private subnets only,
            durability = "sync" (OPS-016 streams live there: an acknowledged XADD must survive a restart or failover)
Needs: pip install python-hcl2
"""
import pathlib
import re
import sys
import tempfile

import hcl2

ROOT = pathlib.Path(__file__).resolve().parent
WORKFLOW = ROOT.parents[1] / ".github" / "workflows" / "infra.yml"
# Resource types whose AWS schema has no `tags` argument.
UNTAGGABLE = {
    "terraform_data", "aws_kms_alias", "aws_s3_bucket_public_access_block", "aws_s3_bucket_versioning",
    "aws_s3_bucket_server_side_encryption_configuration", "aws_s3_bucket_object_lock_configuration", "aws_s3_bucket_lifecycle_configuration",
    "aws_s3_bucket_replication_configuration", "aws_iam_role_policy", "aws_iam_role_policy_attachment",
    "aws_route53_record", "aws_db_instance_automated_backups_replication",
    "random_password", "aws_secretsmanager_secret_version",
}
REGION = re.compile(r"\b(ap|me|eu|us|af|ca|sa|il|mx)-(north|south|east|west|central|northeast|southeast)-\d\b")
CRED = re.compile(r"(access_key|secret_key|aws-access-key-id|aws-secret-access-key|AWS_SECRET_ACCESS_KEY|AWS_ACCESS_KEY_ID)\s*[=:]"
                  r"|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----")


def resources(tf_dir):
    for f in sorted(tf_dir.rglob("*.tf")):
        if ".terraform" in f.parts:
            continue
        doc = hcl2.loads(f.read_text())
        for block in doc.get("resource", []):
            for rtype, named in block.items():
                for name, body in named.items():
                    yield f, rtype.strip('"'), name.strip('"'), body


def check(tf_dir, workflow):
    errors = []
    buckets, pabs = {}, {}
    for f, rtype, name, body in resources(tf_dir):
        where = f"{f.relative_to(tf_dir)}: {rtype}.{name}"
        if rtype not in UNTAGGABLE:
            tags = str(body.get("tags", ""))
            if not (re.search(r"\b(var|local)\.tags\b", tags) and "data_class" in tags):
                errors.append(f"tags: {where} must set tags = merge(var.tags, {{ data_class = ... }})")
        if rtype == "aws_db_instance":
            want = {"publicly_accessible": False, "storage_encrypted": True, "backup_retention_period": 35,
                    "deletion_protection": True, "multi_az": True}
            for k, v in want.items():
                if body.get(k) != v:
                    errors.append(f"safety: {where} needs {k} = {str(v).lower()}")
        if rtype == "aws_elasticache_replication_group":
            want = {"transit_encryption_enabled": True, "transit_encryption_mode": "required", "at_rest_encryption_enabled": True,
                    "automatic_failover_enabled": True, "multi_az_enabled": True, "durability": "sync"}
            for k, v in want.items():
                got = body.get(k)
                if (got.strip('"') if isinstance(got, str) else got) != v:
                    errors.append(f"safety: {where} needs {k} = {str(v).lower() if isinstance(v, bool) else v}")
            if not isinstance(body.get("num_cache_clusters"), int) or body["num_cache_clusters"] < 2:
                errors.append(f"safety: {where} needs num_cache_clusters >= 2 (a replica in a second AZ)")
            if not body.get("auth_token") or not body.get("kms_key_id"):
                errors.append(f"safety: {where} needs auth_token and a customer kms_key_id")
            if "aws_elasticache_subnet_group" not in str(body.get("subnet_group_name", "")):
                errors.append(f"safety: {where} must sit in the cell's private subnet group (no public endpoint)")
        if rtype == "aws_elasticache_subnet_group" and "aws_subnet.private" not in str(body.get("subnet_ids", "")):
            errors.append(f"safety: {where} must use the private subnets only")
        if rtype == "aws_s3_bucket":
            buckets[(f.parent, name)] = where
        if rtype == "aws_s3_bucket_public_access_block":
            if all(body.get(k) is True for k in ("block_public_acls", "block_public_policy", "ignore_public_acls",
                                                 "restrict_public_buckets")):
                pabs[(f.parent, name)] = True
    for key, where in buckets.items():
        if key not in pabs:
            errors.append(f"safety: {where} has no full aws_s3_bucket_public_access_block with the same name")
    module = tf_dir / "modules" / "cell"
    for f in sorted(module.rglob("*.tf")):
        if f.name != "residency.tf" and REGION.search(f.read_text()):
            errors.append(f"regions: {f.relative_to(tf_dir)} hard-codes a region; pass it as a variable")
    scan = [f for f in tf_dir.rglob("*") if f.is_file() and f.suffix in {".tf", ".hcl", ".tfvars", ".json", ".yml"}]
    if workflow.exists():
        scan.append(workflow)
        text = workflow.read_text()
        if "id-token: write" not in text or "role-to-assume" not in text:
            errors.append("creds: infra workflow must use OIDC (permissions id-token: write + role-to-assume)")
    for f in scan:
        if ".terraform" in f.parts:
            continue
        for n, line in enumerate(f.read_text().splitlines(), 1):
            if CRED.search(line):
                errors.append(f"creds: {f.name}:{n} looks like a static credential")
    return errors


BAD = {
    "untagged": 'resource "aws_vpc" "x" {\n  cidr_block = "10.0.0.0/16"\n}\n',
    "public_db": 'resource "aws_db_instance" "x" {\n  publicly_accessible = true\n  storage_encrypted = true\n'
                 '  backup_retention_period = 35\n  deletion_protection = true\n  multi_az = true\n'
                 '  tags = merge(var.tags, { data_class = "phi" })\n}\n',
    "open_bucket": 'resource "aws_s3_bucket" "x" {\n  tags = merge(var.tags, { data_class = "phi" })\n}\n',
    "static_key": 'provider "aws" {\n  access_key = "x"\n}\n',
    "region": 'variable "r" {\n  default = "ap-south-1"\n}\n',
    "plaintext_valkey": 'resource "aws_elasticache_replication_group" "x" {\n  transit_encryption_enabled = false\n'
                        '  at_rest_encryption_enabled = true\n  automatic_failover_enabled = true\n  multi_az_enabled = true\n'
                        '  num_cache_clusters = 1\n  tags = merge(var.tags, { data_class = "ops" })\n}\n',
    "volatile_streams": 'resource "aws_elasticache_replication_group" "x" {\n  transit_encryption_enabled = true\n'
                        '  transit_encryption_mode = "required"\n  at_rest_encryption_enabled = true\n'
                        '  automatic_failover_enabled = true\n  multi_az_enabled = true\n  durability = "disabled"\n'
                        '  num_cache_clusters = 2\n  tags = merge(var.tags, { data_class = "ops" })\n}\n',
}
EXPECT = {"untagged": "tags:", "public_db": "publicly_accessible", "open_bucket": "public_access_block",
          "static_key": "creds:", "region": "regions:", "plaintext_valkey": "transit_encryption_enabled",
          "volatile_streams": "durability"}


def selftest():
    ok = True
    for case, src in BAD.items():
        with tempfile.TemporaryDirectory() as d:
            mod = pathlib.Path(d) / "modules" / "cell"
            mod.mkdir(parents=True)
            (mod / "main.tf").write_text(src)
            errs = check(pathlib.Path(d), pathlib.Path(d) / "none.yml")
            hit = any(EXPECT[case] in e for e in errs)
            print(f"selftest {case}: {'caught' if hit else 'MISSED'}")
            ok &= hit
    errs = check(ROOT, WORKFLOW)
    print(f"selftest repo-clean: {'ok' if not errs else 'FAILED'}")
    return ok and not errs


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(0 if selftest() else 1)
    errs = check(ROOT, WORKFLOW)
    for e in errs:
        print(e)
    print(f"check-terraform: {len(errs)} problem(s)")
    sys.exit(1 if errs else 0)
