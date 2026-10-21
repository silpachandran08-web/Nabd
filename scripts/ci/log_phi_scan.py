#!/usr/bin/env python3
"""OPS-028: scan a log stream (JSON lines or plain text) for synthetic PHI markers and for PHI that escaped masking.
Usage: log_phi_scan.py [--report out.json] FILE...   ('-' = stdin). Exit 1 on any hit.
Hits are reported by logger and rule only, with the match itself cut to its first 3 characters, so the report can go into an issue.
Markers: SYNTH-PHI-<token> (seeded into staging data by the synthetic-patient fixtures).
Escaped PHI shapes (the masker turns these into +91******5678 / a***@*** / ******): e-mail, +CC phone, 12-digit Aadhaar-like,
Emirates ID, and a 4-8 digit run next to otp|code|pin."""
import json, re, sys

RULES = {
    "marker": re.compile(r"SYNTH-PHI-[A-Za-z0-9]+"),
    "email": re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[A-Za-z]{2,}"),
    "phone": re.compile(r"(?<![\w*])\+\d{8,15}"),
    "national_id": re.compile(r"(?<![\w.-])(?:784-?\d{4}-?\d{7}-?\d|\d{4}[ -]\d{4}[ -]\d{4}|\d{12})(?![\w.-])"),
    "otp": re.compile(r"(?i)\b(?:otp|code|pin)\b[^\w\n]{0,4}(?:is|=|:)?\s*\d{4,8}\b"),
}
LOGGER_FALLBACK = re.compile(r"\b((?:[a-z_][\w]*\.){2,}[A-Z]\w*)\b")


def logger_of(line):
    try:
        v = json.loads(line)
        if isinstance(v, dict):
            return str(v.get("logger") or "unknown"), line
    except ValueError:
        pass
    m = LOGGER_FALLBACK.search(line)
    return (m.group(1) if m else "unknown"), line


def scan_lines(lines):
    hits = []
    for n, line in enumerate(lines, 1):
        logger, text = logger_of(line)
        for rule, rx in RULES.items():
            m = rx.search(text)
            if m:
                hits.append({"line": n, "logger": logger, "rule": rule, "sample": m.group()[:3] + "..."})
    return hits


def main(argv):
    report = None
    if argv[:1] == ["--report"]:
        report, argv = argv[1], argv[2:]
    lines = []
    for f in argv or ["-"]:
        lines += (sys.stdin if f == "-" else open(f, encoding="utf-8", errors="replace")).read().splitlines()
    hits = scan_lines(lines)
    if report:
        json.dump({"lines": len(lines), "hits": hits}, open(report, "w"), indent=1)
    for h in hits[:50]:
        print(f"log-phi-scan: {h['rule']} in logger {h['logger']} (line {h['line']}, starts {h['sample']})")
    print(f"log-phi-scan: {len(lines)} lines, {len(hits)} hits")
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
