#!/usr/bin/env python3
"""OPS-020: no cache VALUE reaches a log line or a metric (keys may, they hold ids only, never PHI).

  cache_values.py check                 static: in backend main code, a variable read from Valkey (opsForValue/opsForHash
                                        get, multiGet, getAndSet, getAndDelete) never appears in a log call or a meter tag
  cache_values.py check --samples DIR   runtime: DIR/values.txt (every value the cache tests stored, one per line) must
                                        not occur in DIR/logs.txt or DIR/metrics.txt (written by Ops020CacheApiTest)
shortcut: regex over Java source, one statement per line for the read; a value passed through a helper method is not
followed. Upgrade to an ArchUnit/PMD data-flow rule if caches multiply.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "backend" / "src" / "main" / "java"
READ = re.compile(r"\b(\w+)\s*=\s*[^;]*\.ops?For(?:Value|Hash)\(\)\s*\.\s*(?:get|multiGet|getAndSet|getAndDelete|entries|values)\(")
SINK = re.compile(r"\b(?:log|logger|LOG)\s*\.\s*(?:trace|debug|info|warn|error)\s*\(|\bTags?\.of\(|\.tags?\(|"
                  r"\bmeters?\s*\.\s*(?:counter|gauge|timer|summary)\s*\(")


def scan_source(text):
    """Names of cache-read variables that reach a log or meter call in this source text."""
    names = {m.group(1) for m in READ.finditer(text)}
    hits = []
    for n, line in enumerate(text.splitlines(), 1):
        if SINK.search(line):
            for name in names:
                if re.search(rf"\b{re.escape(name)}\b", line.split("(", 1)[1] if "(" in line else line):
                    hits.append((n, name))
    return hits


def check_static(src=SRC):
    errors = []
    for f in sorted(src.rglob("*.java")):
        text = f.read_text()
        if "opsFor" not in text:
            continue
        for n, name in scan_source(text):
            errors.append(f"{f.relative_to(src)}:{n}: cache value '{name}' reaches a log line or metric")
    return errors


def check_samples(d):
    d = pathlib.Path(d)
    values = [v.strip() for v in (d / "values.txt").read_text().splitlines() if len(v.strip()) >= 4]
    if not values:
        return [f"{d}/values.txt lists no cache values: the runtime scan would prove nothing"]
    errors = []
    for sink in ("logs.txt", "metrics.txt"):
        text = (d / sink).read_text()
        for v in values:
            if v in text:
                errors.append(f"{sink}: contains a cached value ({len(v)} chars)")  # never echo the value itself
    return errors


if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] != "check":
        print(__doc__)
        sys.exit(2)
    errs = check_static()
    if "--samples" in sys.argv:
        errs += check_samples(sys.argv[sys.argv.index("--samples") + 1])
    for e in errs:
        print(e)
    print(f"cache_values: {len(errs)} problem(s)")
    sys.exit(1 if errs else 0)
