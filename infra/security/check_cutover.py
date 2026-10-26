#!/usr/bin/env python3
"""OPS-056.5 post-cutover verification. Each mode exits 1 on a finding.
  --resolve HOST...         every host's canonical name must be the Mumbai ALB (*.ap-south-1.elb.amazonaws.com)   [live DNS]
  --egress-log FILE         no line may name onrender.com / vercel.app / vercel.com (egress or access logs)         [live logs]
  --csp VALUE               a Content-Security-Policy string whose connect-src may only hold 'self' and the given --api-origin
  --certificate FILE        destruction certificate: all fields filled, no REPLACE_ placeholder left
"""
import re
import socket
import sys

BANNED = re.compile(r"onrender\.com|vercel\.app|vercel\.com", re.I)
FIELDS = ("Provider", "Service ids", "Data store destroyed", "Destroyed on", "Destroyed by", "Evidence reference", "Verified by")


def resolve(hosts, getter=socket.gethostbyname_ex):
    out = []
    for h in hosts:
        try:
            canon = getter(h)[0]
        except OSError as e:
            out.append(f"{h}: does not resolve ({e})")
            continue
        if not canon.endswith(".ap-south-1.elb.amazonaws.com"):
            out.append(f"{h}: resolves to {canon}, not the Mumbai ingress")
    return out


def egress(text):
    return [f"line {n}: {l.strip()[:120]}" for n, l in enumerate(text.splitlines(), 1) if BANNED.search(l)]


def csp(value, api_origin):
    m = re.search(r"connect-src([^;]*)", value)
    if not m:
        return ["no connect-src directive"]
    allowed = {"'self'", api_origin}
    return [f"connect-src allows {s}" for s in m.group(1).split() if s not in allowed]


def certificate(text):
    out = [f"field missing or empty: {f}" for f in FIELDS if not re.search(rf"^[ \t]*[-*]?[ \t]*\**{f}\**[ \t]*:[ \t]*\S", text, re.M | re.I)]
    return out + (["placeholder REPLACE_ left"] if "REPLACE_" in text else [])


if __name__ == "__main__":
    a = sys.argv[1:]
    if not a:
        sys.exit(__doc__)
    if a[0] == "--resolve":
        bad = resolve(a[1:])
    elif a[0] == "--egress-log":
        bad = egress(open(a[1]).read())
    elif a[0] == "--csp":
        bad = csp(a[1], a[a.index("--api-origin") + 1] if "--api-origin" in a else "")
    elif a[0] == "--certificate":
        bad = certificate(open(a[1]).read())
    else:
        sys.exit(__doc__)
    print("\n".join(bad) if bad else "ok")
    sys.exit(1 if bad else 0)
