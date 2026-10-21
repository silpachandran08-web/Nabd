#!/usr/bin/env python3
"""OPS-015 event contract gate (docs/architecture/events.md).

  python3 scripts/ci/events.py check [--root DIR] [--samples DIR]

Fails (exit 1) when: a DomainEventType has no api/events/<type>.schema.json or a schema has no catalogue entry; a schema
is not valid draft 2020-12, allows extra fields, lacks tenantId/version, has a field on the PHI denylist or an unbounded
string (no format/enum/const/pattern); an example does not validate or its envelope is >= 2 KB. --samples DIR also checks
every *.json envelope there (written by the backend contract test) against the envelope and its data schema."""
import glob, json, os, re, sys
from jsonschema import Draft202012Validator, FormatChecker

MAX_BYTES = 2048
ENUM = "backend/src/main/java/com/nabd/hms/common/DomainEventType.java"


def size(envelope):
    return len(json.dumps(envelope, separators=(",", ":"), ensure_ascii=False).encode())


def denylist(root):
    return [w.strip().lower() for w in open(f"{root}/api/events/phi-denylist.txt") if w.strip() and not w.startswith("#")]


def phi_fields(data, deny):
    return sorted(k for k in data if any(w in k.lower() for w in deny))


def sample_envelope(t, data):
    return {"specversion": "1.0", "id": "123456789", "source": "core/" + t.split(".")[0], "type": t,
            "time": "2026-10-20T04:30:00.123456Z", "subject": "44444444-4444-4444-8444-444444444444",
            "tenantid": data.get("tenantId", "11111111-1111-4111-8111-111111111111"), "region": "ksa",
            "traceparent": "00-" + "a" * 32 + "-" + "b" * 16 + "-01", "dataschema": "urn:nabd:event-schema:" + t,
            "datacontenttype": "application/json", "data": data}


def errors_of(validator, doc):
    return [f"{'/'.join(map(str, e.path)) or '<root>'}: {e.message}" for e in validator.iter_errors(doc)]


def check(root=".", samples=None):
    problems = []
    deny = denylist(root)
    catalogue = set(re.findall(r'\("([a-z_.]+\.v\d+)",\s*"core/[a-z]+"\)', open(f"{root}/{ENUM}").read()))
    files = {os.path.basename(f)[:-len(".schema.json")]: f for f in glob.glob(f"{root}/api/events/*.schema.json")}
    files.pop("cloudevent", None)
    env = Draft202012Validator(json.load(open(f"{root}/api/events/cloudevent.schema.json")), format_checker=FormatChecker())
    for t in sorted(catalogue - files.keys()):
        problems.append(f"{t}: in DomainEventType but no api/events/{t}.schema.json")
    for t in sorted(files.keys() - catalogue):
        problems.append(f"{t}: schema has no DomainEventType entry")
    validators = {}
    for t, f in sorted(files.items()):
        s = json.load(open(f))
        try:
            Draft202012Validator.check_schema(s)
        except Exception as e:  # SchemaError
            problems.append(f"{t}: not a valid draft 2020-12 schema: {e}"); continue
        if s.get("additionalProperties") is not False:
            problems.append(f"{t}: additionalProperties must be false")
        if s.get("$id") != "urn:nabd:event-schema:" + t:
            problems.append(f"{t}: $id must be urn:nabd:event-schema:{t}")
        props = s.get("properties", {})
        for k in ("tenantId", "version"):
            if k not in s.get("required", []):
                problems.append(f"{t}: {k} must be required")
        for k in phi_fields(props, deny):
            problems.append(f"{t}: field '{k}' matches the PHI denylist")
        for k, p in props.items():
            if p.get("type") == "string" and not any(x in p for x in ("format", "enum", "const", "pattern")):
                problems.append(f"{t}: string field '{k}' is unbounded (add format, enum, const or pattern)")
        v = validators[t] = Draft202012Validator(s, format_checker=FormatChecker())
        if not s.get("examples"):
            problems.append(f"{t}: needs at least one example")
        for ex in s.get("examples", []):
            problems += [f"{t} example: {e}" for e in errors_of(v, ex)]
            e = sample_envelope(t, ex)
            problems += [f"{t} example envelope: {x}" for x in errors_of(env, e)]
            if size(e) >= MAX_BYTES:
                problems.append(f"{t}: example envelope is {size(e)} bytes (limit < {MAX_BYTES})")
    for f in sorted(glob.glob(f"{samples}/*.json")) if samples else []:
        e = json.load(open(f)); name = os.path.basename(f)
        problems += [f"{name}: envelope {x}" for x in errors_of(env, e)]
        t, data = e.get("type"), e.get("data") or {}
        if t not in validators:
            problems.append(f"{name}: no schema for type {t}")
        else:
            problems += [f"{name}: data {x}" for x in errors_of(validators[t], data)]
        problems += [f"{name}: field '{k}' matches the PHI denylist" for k in phi_fields(data, deny)]
        if size(e) >= MAX_BYTES:
            problems.append(f"{name}: envelope is {size(e)} bytes (limit < {MAX_BYTES})")
    return catalogue, problems


def main(argv):
    if not argv or argv[0] != "check":
        print(__doc__); return 2
    root, samples, it = ".", None, iter(argv[1:])
    for a in it:
        if a == "--root": root = next(it)
        elif a == "--samples": samples = next(it)
    catalogue, problems = check(root, samples)
    for p in problems:
        print("events:", p)
    print(f"events: {len(catalogue)} type(s), {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
