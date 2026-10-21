#!/usr/bin/env python3
"""OPS-026.4: one trace must span BFF -> Core -> Comms (traceparent joins them). Staging check against the cell's Tempo (Needs external action to run):
  python3 verify_trace.py https://tempo.obs.cell.internal:3200 <trace-id>
Tempo returns the OTLP JSON trace; the services are the resource attribute service.name values."""
import json, sys, urllib.request

WANT = {"bff", "core", "comms"}


def services(trace):
    out = set()
    for b in trace.get("batches", trace.get("resourceSpans", [])):
        for a in b.get("resource", {}).get("attributes", []):
            if a["key"] == "service.name":
                out.add(a["value"]["stringValue"])
    return out


def missing(trace):
    return sorted(WANT - services(trace))


def selftest():
    mk = lambda *n: {"batches": [{"resource": {"attributes": [{"key": "service.name", "value": {"stringValue": s}}]}} for s in n]}
    assert missing(mk("bff", "core", "comms")) == []
    assert missing(mk("bff", "core")) == ["comms"]  # trace broken at the Comms hop
    assert missing({}) == ["bff", "comms", "core"]
    print("verify_trace selftest ok")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
        sys.exit(0)
    with urllib.request.urlopen(f"{sys.argv[1]}/api/traces/{sys.argv[2]}", timeout=10) as r:
        m = missing(json.load(r))
    print("trace spans bff, core, comms" if not m else f"trace is missing services: {m}")
    sys.exit(1 if m else 0)
