#!/usr/bin/env python3
"""Fails when a log statement's arguments are named phone, otp, code, token, reason, note, name or diagnosis.
Usage: log-phi-scan.py [dir ...]   (default backend/src/main/java)
Suppress a justified hit (e.g. a queue token number) with `// log-phi-ok: <why>` on the log line.
Mirrors .semgrep/log-phi.yml, which is the same rule for Semgrep users."""
import re, sys, pathlib

CALL = re.compile(r'\blog(?:ger)?\.(?:trace|debug|info|warn|error)\(')
WORD = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')
BAD = {'phone', 'otp', 'code', 'token', 'reason', 'note', 'name', 'diagnosis'}

def args_of(text, start):
    depth, i, in_str = 1, start, False
    while i < len(text) and depth:
        c = text[i]
        if in_str:
            if c == '\\': i += 1
            elif c == '"': in_str = False
        elif c == '"': in_str = True
        elif c == '(': depth += 1
        elif c == ')': depth -= 1
        i += 1
    return text[start:i - 1]

def bad_words(args):
    args = re.sub(r'"(?:\\.|[^"\\])*"', '""', args)  # drop the message literal(s)
    hits = set()
    args = re.sub(r'\.id\(\)', '', re.sub(r'\b(\w+)\.id\(\)', 'X', args))  # note.id() is an id, not the note
    for w in WORD.findall(args):
        parts = [p.lower() for p in re.findall(r'[A-Z]?[a-z]+|[A-Z]+(?![a-z])', w)]
        hits |= {w for p in parts if p in BAD}
    return sorted(hits)

def scan(roots):
    out = []
    for root in roots:
        for f in pathlib.Path(root).rglob('*.java'):
            text = f.read_text()
            for m in CALL.finditer(text):
                line = text.count('\n', 0, m.start()) + 1
                if 'log-phi-ok' in text.splitlines()[line - 1]:
                    continue
                hits = bad_words(args_of(text, m.end()))
                if hits:
                    out.append(f'{f}:{line}: log argument(s) {", ".join(hits)}')
    return out

if __name__ == '__main__':
    found = scan(sys.argv[1:] or ['backend/src/main/java'])
    print('\n'.join(found) or 'log-phi-scan: clean')
    sys.exit(1 if found else 0)
