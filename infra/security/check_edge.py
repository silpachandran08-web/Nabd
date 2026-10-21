#!/usr/bin/env python3
"""OPS-056 static checks on the edge Terraform (edge_*.tf). No cloud needed.
  python3 infra/security/check_edge.py             # check the repo
  python3 infra/security/check_edge.py --selftest  # each rule must fail on a mutated copy
"""
import pathlib
import re
import sys

DIR = pathlib.Path(__file__).resolve().parents[1] / "terraform" / "modules" / "cell" / "aws"
# AWS policies that allow TLS 1.2/1.3 only, forward-secrecy AEAD ciphers (no CBC-SHA1, no 3DES/RC4).
OK_POLICIES = {"ELBSecurityPolicy-TLS13-1-2-2021-06", "ELBSecurityPolicy-TLS13-1-2-Res-2021-06", "ELBSecurityPolicy-TLS13-1-3-2021-06"}


def problems(files):
    src = "\n".join(files[k] for k in sorted(files))
    out = []
    pol = re.findall(r'ssl_policy\s*=\s*"([^"]+)"', src)
    if not pol or any(p not in OK_POLICIES for p in pol):
        out.append(f"tls: ssl_policy must be one of {sorted(OK_POLICIES)}, got {pol}")
    m = re.search(r'strict_transport_security_header_value\s*=\s*"([^"]+)"', src)
    age = re.search(r"max-age=(\d+)", m.group(1)) if m else None
    if not (m and age and int(age.group(1)) >= 31536000 and "preload" in m.group(1) and "includeSubDomains" in m.group(1)):
        out.append("hsts: HTTPS listener needs max-age>=31536000; includeSubDomains; preload")
    if not re.search(r'port\s*=\s*80\s+protocol\s*=\s*"HTTP"\s+default_action\s*\{\s*type\s*=\s*"redirect"', src):
        out.append("http: port 80 listener must only redirect")
    for k in ("drop_invalid_header_fields", "enable_deletion_protection"):
        if not re.search(rf"{k}\s*=\s*true", src):
            out.append(f"alb: {k} must be true")
    if 'validation_method         = "DNS"' not in src:
        out.append("cert: ACM certificate must use DNS validation (auto-renewal)")
    if 'scope = "REGIONAL"' not in src or "aws_wafv2_web_acl_association" not in src:
        out.append("waf: regional web ACL must be associated with the load balancer")
    if not re.search(r'rate_based_statement\s*\{[^}]*aggregate_key_type\s*=\s*"IP"', src):
        out.append("waf: per-IP rate-based rule missing")
    for g in ("AWSManagedRulesCommonRuleSet", "AWSManagedRulesKnownBadInputsRuleSet"):
        if g not in src:
            out.append(f"waf: managed group {g} missing")
    if re.search(r"override_action\s*\{\s*count", src):
        out.append("waf: managed rules must block, not count")
    if "cidr_blocks = [var.vpc_cidr]" not in src:
        out.append("sg: load balancer egress must stay inside the VPC")
    if not re.search(r'single_header\s*\{\s*name\s*=\s*"authorization"', src):
        out.append("waf: Authorization header must be redacted in WAF logs")
    return out


def load():
    return {p.name: p.read_text() for p in DIR.glob("edge_*.tf")}


def selftest():
    good = load()
    assert not problems(good), problems(good)
    muts = [("edge_alb.tf", 'ELBSecurityPolicy-TLS13-1-2-2021-06', 'ELBSecurityPolicy-TLS-1-0-2015-04'),
            ("edge_alb.tf", "; preload", ""), ("edge_alb.tf", '"redirect"', '"forward"'),
            ("edge_tls.tf", '"DNS"', '"EMAIL"'), ("edge_waf.tf", 'aggregate_key_type = "IP"', 'aggregate_key_type = "FORWARDED_IP"'),
            ("edge_waf.tf", "AWSManagedRulesKnownBadInputsRuleSet", "x"), ("edge_waf.tf", "none {}", "count {}"),
            ("edge_waf.tf", '"authorization"', '"x-none"'), ("edge_network.tf", "cidr_blocks = [var.vpc_cidr]", 'cidr_blocks = ["0.0.0.0/0"]')]
    for f, a, b in muts:
        assert a in good[f], f"mutation target {a!r} not found in {f}"
        bad = dict(good)
        bad[f] = good[f].replace(a, b)
        assert problems(bad), f"mutation not caught: {a!r}"
    print(f"selftest ok ({len(muts)} mutations caught)")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
        sys.exit(0)
    p = problems(load())
    print("\n".join(p) if p else "edge terraform ok")
    sys.exit(1 if p else 0)
