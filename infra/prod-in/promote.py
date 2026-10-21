#!/usr/bin/env python3
"""OPS-056: render the prod-in manifests for ONE promoted release. Only a sha256 digest is accepted, never a tag.
  python3 infra/prod-in/promote.py --core ghcr.io/o/nabd-core@sha256:<64 hex> --web ghcr.io/o/nabd-web@sha256:<64 hex> --out dir
The workflow verifies the cosign signature and that the same digests run in staging-in BEFORE it calls this (.github/workflows/prod-in.yml)."""
import argparse
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
REF = re.compile(r"^(ghcr\.io/[a-z0-9._-]+/[a-z0-9._/-]+)@sha256:([0-9a-f]{64})$")
FILES = {"core.yaml": "core", "worker.yaml": "core", "bff.yaml": "web"}


def render(core, web, out):
    for r in (core, web):
        if not REF.match(r):
            sys.exit(f"refused: {r!r} is not <registry image>@sha256:<64 hex> (tags are not allowed)")
    out.mkdir(parents=True, exist_ok=True)
    for f, which in FILES.items():
        ref = core if which == "core" else web
        text = (HERE / f).read_text()
        text, n = re.subn(r"image: \S+@sha256:REPLACE_DIGEST", f"image: {ref}", text)
        assert n == 1, f"{f}: expected one image placeholder, found {n}"
        (out / f).write_text(text)
    for f in ("targets.yaml", "verify-images.yaml"):
        (out / f).write_text((HERE / f).read_text())


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--core", required=True)
    ap.add_argument("--web", required=True)
    ap.add_argument("--out", required=True, type=pathlib.Path)
    a = ap.parse_args()
    render(a.core, a.web, a.out)
    print(f"rendered {a.out}")
