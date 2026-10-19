# OPS-052: run from infra/terraform with `tflint --init && tflint --recursive --config "$PWD/.tflint.hcl"`.
plugin "terraform" {
  enabled = true
  preset  = "recommended"
}

plugin "aws" {
  enabled = true
  version = "0.36.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"
}
