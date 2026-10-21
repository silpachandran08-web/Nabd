#!/usr/bin/env python3
"""OPS-058: static checks of infra/terraform/modules/cell/aws/secrets_*.tf, with a mutation per rule to prove it fails.
Run: python3 -m unittest scripts/ci/test_ops058_secrets.py   (needs python-hcl2, like check-terraform.py)"""
import pathlib
import re
import unittest

import hcl2

AWS = pathlib.Path(__file__).resolve().parents[2] / "infra" / "terraform" / "modules" / "cell" / "aws"
FILES = ["secrets_kms.tf", "secrets_iam.tf", "secrets_audit.tf", "variables_ops058.tf", "outputs_ops058.tf"]
CLASSES = {"field-encryption", "backups", "object-storage", "cache-projection"}


def check(src):
    """src: {filename: text}. Returns the list of problems."""
    e = []
    for name, text in src.items():
        hcl2.loads(text)
    kms, iam, audit = src["secrets_kms.tf"], src["secrets_iam.tf"], src["secrets_audit.tf"]
    m = re.search(r's58_key_classes\s*=\s*toset\(\[(.*?)\]\)', kms, re.S)
    have = set(re.findall(r'"([a-z-]+)"', m.group(1))) if m else set()
    if not CLASSES <= have:
        e.append(f"kms: missing key classes {sorted(CLASSES - have)}")
    if len(re.findall(r'enable_key_rotation\s*=\s*true', kms)) != len(re.findall(r'resource "aws_kms_key"', kms)):
        e.append("kms: every key needs enable_key_rotation = true")
    if 'resource "aws_kms_replica_key"' not in kms:
        e.append("kms: DR replicas missing")
    if re.search(r'Sid\s*=\s*"AccountAdminNoUse".*?Action\s*=\s*"kms:\*"', kms, re.S) or 'Action    = "kms:*"' in kms:
        e.append("kms: the account root must not get kms:* (it would allow use from any cell)")
    if "aws:PrincipalTag/cell_name" not in kms or "DenyOtherCells" not in kms:
        e.append("kms: use must be restricted to this cell's tagged principals")
    if "aws:PrincipalTag/tenant_id" not in iam or not re.search(r'tenants/\$\$\{aws:PrincipalTag/tenant_id\}/\*', iam):
        e.append("iam: tenant reader must be scoped by the tenant_id session tag")
    if not re.search(r'Sid = "NoTenantTagNoAccess".*?Null', iam):
        e.append("iam: tenant reader needs a deny when the tenant_id tag is absent")
    for role in ("platform_operator", "tenant_operator"):
        body = iam.split(f'resource "aws_iam_role_policy" "secrets_{role}"')[1].split("\nresource")[0]
        if "GetSecretValue" in body.split("Deny")[0]:
            e.append(f"iam: {role} must not be able to read values")
    for stmt in re.findall(r'\{[^{}]*GetSecretValue[^{}]*\}', iam):
        if 'Effect = "Allow"' in stmt and re.search(r'Resource\s*=\s*"\*"', stmt):
            e.append("iam: GetSecretValue allowed on Resource *")
    if "secret_string" in iam + audit + kms or "random_password" in iam + audit + kms:
        e.append("secrets: no secret values in Terraform")
    if "GetSecretValue" not in audit or "kms_key_id" not in audit or "aws_cloudwatch_event_target" not in audit:
        e.append("audit: reads must flow to an encrypted log group")
    return e


def load():
    return {f: (AWS / f).read_text() for f in FILES}


class Ops058Secrets(unittest.TestCase):
    def test_repo_is_clean(self):
        self.assertEqual(check(load()), [])

    def mutated(self, f, old, new):
        s = load()
        self.assertIn(old, s[f])
        s[f] = s[f].replace(old, new)
        return check(s)

    def test_rules_fail_on_bad_samples(self):
        cases = [
            ("secrets_kms.tf", '"cache-projection", ', "", "missing key classes"),
            ("secrets_kms.tf", "enable_key_rotation     = true", "enable_key_rotation     = false", "rotation"),
            ("secrets_kms.tf", '"DenyOtherCells"', '"Other"', "tagged principals"),
            ("secrets_iam.tf", "$${aws:PrincipalTag/tenant_id}", "*", "tenant_id session tag"),
            ("secrets_iam.tf", 'Sid = "NoTenantTagNoAccess"', 'Sid = "X"', "tag is absent"),
            ("secrets_iam.tf", '"secretsmanager:CreateSecret", "secretsmanager:PutSecretValue", "secretsmanager:UpdateSecret", "secretsmanager:RotateSecret", "secretsmanager:DescribeSecret", "secretsmanager:TagResource"], Resource = local.s58_tenants_arn',
             '"secretsmanager:GetSecretValue"], Resource = local.s58_tenants_arn', "read values"),
            ("secrets_iam.tf", 'Action = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"], Resource = local.s58_platform_arn',
             'Action = ["secretsmanager:GetSecretValue"], Resource = "*"', "Resource *"),
            ("secrets_audit.tf", "kms_key_id        = aws_kms_key.s58[\"secret-audit-logs\"].arn", "", "encrypted log group"),
        ]
        for f, old, new, want in cases:
            with self.subTest(want=want):
                errs = self.mutated(f, old, new)
                self.assertTrue(any(want in x for x in errs), errs)

    def test_paths_and_roles_are_separate(self):
        iam = load()["secrets_iam.tf"]
        self.assertIn("cell/${var.name}/platform/*", iam)
        self.assertIn("cell/${var.name}/tenants/*", iam)
        self.assertEqual(len(re.findall(r'resource "aws_iam_role" ', iam)), 4)


if __name__ == "__main__":
    unittest.main()
