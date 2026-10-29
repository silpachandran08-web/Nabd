#!/usr/bin/env python3
"""OPS-059: print infra/prod-in/*.yaml for the DR region (Mumbai region name swapped, target group ARNs filled). Text substitution only."""
import argparse, pathlib, sys

PROD = pathlib.Path(__file__).resolve().parent.parent / "prod-in"
FILES = ("core.yaml", "worker.yaml", "bff.yaml", "targets.yaml")


def render(dr_region, core_tg, bff_tg, primary="ap-south-1"):
    if dr_region == primary:
        raise SystemExit("dr region equals the primary region")
    out = []
    for f in FILES:
        t = (PROD / f).read_text().replace(primary, dr_region).replace("REPLACE_CORE_TG_ARN", core_tg).replace("REPLACE_BFF_TG_ARN", bff_tg)
        out.append(t.rstrip() + "\n")
    return "---\n".join(out)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--dr-region", required=True)
    ap.add_argument("--core-tg", required=True)
    ap.add_argument("--bff-tg", required=True)
    a = ap.parse_args()
    sys.stdout.write(render(a.dr_region, a.core_tg, a.bff_tg))
