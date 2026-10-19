# OPS-052 policy-as-code, evaluated by conftest on `tofu show -json <plan>` output:
#   conftest test --policy infra/terraform/policy plan.json
package main

import rego.v1

changes := [r |
	some r in input.resource_changes
	r.mode == "managed"
	not "delete" in r.change.actions
]

required_tags := {"cell", "service", "data_class"}

# Every resource whose schema has `tags` must carry the cost/residency tags.
deny contains msg if {
	some r in changes
	"tags" in object.keys(r.change.after)
	have := {k | some k, _ in tag_map(r.change.after.tags)}
	missing := required_tags - have
	count(missing) > 0
	msg := sprintf("%v: missing tags %v", [r.address, sort(missing)])
}

tag_map(t) := t if is_object(t)

tag_map(t) := {} if not is_object(t)

deny contains msg if {
	some r in changes
	r.type == "aws_db_instance"
	r.change.after.publicly_accessible != false
	msg := sprintf("%v: Postgres must not be publicly accessible", [r.address])
}

deny contains msg if {
	some r in changes
	r.type == "aws_db_instance"
	r.change.after.storage_encrypted != true
	msg := sprintf("%v: Postgres storage must be encrypted", [r.address])
}

deny contains msg if {
	some r in changes
	r.type == "aws_db_instance"
	r.change.after.backup_retention_period < 35
	msg := sprintf("%v: PITR window must be 35 days (OPS-054)", [r.address])
}

deny contains msg if {
	some r in changes
	r.type == "aws_db_instance"
	r.change.after.deletion_protection != true
	msg := sprintf("%v: Postgres needs deletion protection (backups immutable for 35 days)", [r.address])
}

# Every bucket needs a sibling public-access block (same address, type swapped) with all four switches on.
deny contains msg if {
	some r in changes
	r.type == "aws_s3_bucket"
	pab := replace(r.address, "aws_s3_bucket.", "aws_s3_bucket_public_access_block.")
	not fully_blocked(pab)
	msg := sprintf("%v: bucket is not fully blocked from public access", [r.address])
}

fully_blocked(address) if {
	some p in changes
	p.address == address
	a := p.change.after
	a.block_public_acls == true
	a.block_public_policy == true
	a.ignore_public_acls == true
	a.restrict_public_buckets == true
}

deny contains msg if {
	some r in changes
	r.type == "aws_s3_bucket_acl"
	startswith(object.get(r.change.after, "acl", ""), "public")
	msg := sprintf("%v: public bucket ACL", [r.address])
}

# The cell is private: no security-group rule opens anything to the internet.
deny contains msg if {
	some r in changes
	r.type == "aws_security_group"
	some rule in object.get(r.change.after, "ingress", [])
	open_cidr(rule)
	msg := sprintf("%v: ingress open to the internet", [r.address])
}

deny contains msg if {
	some r in changes
	r.type in {"aws_security_group_rule", "aws_vpc_security_group_ingress_rule"}
	object.get(r.change.after, "type", "ingress") == "ingress"
	open_cidr(r.change.after)
	msg := sprintf("%v: ingress open to the internet", [r.address])
}

open_cidr(rule) if "0.0.0.0/0" in object.get(rule, "cidr_blocks", [])

open_cidr(rule) if "::/0" in object.get(rule, "ipv6_cidr_blocks", [])

open_cidr(rule) if object.get(rule, "cidr_ipv4", "") == "0.0.0.0/0"

deny contains msg if {
	some r in changes
	r.type == "aws_internet_gateway"
	msg := sprintf("%v: cells have no internet gateway (private subnets only)", [r.address])
}
