#!/usr/bin/env python3
"""OPS-032 coverage and mutation gates.
  quality.py summary  JACOCO_XML [BASE_SHA]   markdown for the PR comment (overall, new code, gated classes)
  quality.py gate     JACOCO_XML [BASE_SHA]   exit 1 when an ENABLED coverage gate fails
  quality.py baseline JACOCO_XML OUT_JSON     record current numbers + date
  quality.py pit      MUTATIONS_XML             exit 1 below threshold; prints score JSON (save as PIT_SCORE_JSON)
  quality.py pit-drop PIT_SCORE_JSON HISTORY  exit 1 when drop > max_drop vs last green
  quality.py pit-pkg  MUTATIONS_XML             FIN-010: exit 1 when a configured package (pit_packages) scores below its minimum
"""
import fnmatch, json, subprocess, sys, datetime as dt
import xml.etree.ElementTree as ET

CFG = json.load(open("ci/quality-gates.json"))
today = lambda: dt.date.fromisoformat(__import__("os").environ.get("GATE_TODAY", dt.date.today().isoformat()))
def on(d): return d is not None and dt.date.fromisoformat(d) <= today()
pct = lambda c, m: 100.0 if c + m == 0 else 100.0 * c / (c + m)

def counters(el):
    return {c.get("type"): (int(c.get("covered")), int(c.get("missed"))) for c in el.findall("counter")}

def load(xml):
    root = ET.parse(xml).getroot()
    cls = {c.get("name"): counters(c) for p in root.findall("package") for c in p.findall("class")}
    files = {}  # "pkg/File.java" -> {line: covered?}
    for p in root.findall("package"):
        for sf in p.findall("sourcefile"):
            files[p.get("name") + "/" + sf.get("name")] = {int(l.get("nr")): int(l.get("ci")) > 0 for l in sf.findall("line")}
    return counters(root), cls, files

def gated(cls):
    """Aggregate nested classes (Foo$Bar) into their outer class per configured pattern."""
    out = {}
    for pat in CFG["coverage"]["classes"]:
        agg = {"LINE": [0, 0], "BRANCH": [0, 0]}
        n = 0
        for name, c in cls.items():
            if fnmatch.fnmatch(name.split("$")[0], pat):
                n += 1
                for k in agg:
                    cv, ms = c.get(k, (0, 0)); agg[k][0] += cv; agg[k][1] += ms
        out[pat] = (n, pct(*agg["LINE"]), pct(*agg["BRANCH"]))
    return out

def new_code(files, base):
    cov = tot = 0
    diff = subprocess.run(["git", "diff", "-U0", f"{base}...HEAD", "--", "backend/src/main/java"], capture_output=True, text=True, check=True).stdout
    cur, added = None, {}
    for ln in diff.splitlines():
        if ln.startswith("+++ b/"):
            cur = ln[6:].split("src/main/java/", 1)[1]
        elif ln.startswith("@@") and cur:
            h = ln.split("+")[1].split(" ")[0]; s, _, n = h.partition(",")
            added.setdefault(cur, []).extend(range(int(s), int(s) + int(n or 1)))
    for f, lines in added.items():
        for nr in lines:
            if nr in files.get(f, {}):  # only executable lines jacoco knows
                tot += 1; cov += files[f][nr]
    return None if tot == 0 else 100.0 * cov / tot, tot

def evaluate(xml, base):
    ov, cls, files = load(xml)
    c, k = CFG["coverage"], []
    ol = pct(*ov["LINE"]); ob = pct(*ov["BRANCH"])
    nc = new_code(files, base) if base else (None, 0)
    rows = gated(cls)
    if on(c["overall_enabled_from"]) and ol < c["overall_min_line"]: k.append(f"overall line {ol:.1f}% < {c['overall_min_line']}%")
    if on(c["new_code_enabled_from"]) and nc[0] is not None and nc[0] < c["new_code_min"]: k.append(f"new code {nc[0]:.1f}% < {c['new_code_min']}%")
    if on(c["per_class_enabled_from"]):
        for pat, (n, l, b) in rows.items():
            if n == 0: k.append(f"{pat}: no class matched (renamed?)")
            elif l < c["per_class_min_line"] or b < c["per_class_min_branch"]:
                k.append(f"{pat}: line {l:.1f}% / branch {b:.1f}% below {c['per_class_min_line']}/{c['per_class_min_branch']}")
    return ol, ob, nc, rows, k

def pit_score(xml):
    ms = ET.parse(xml).getroot().findall("mutation")
    per = {}
    for m in ms:
        if m.get("status") not in ("NON_VIABLE", "RUN_ERROR"):
            d = per.setdefault(m.findtext("mutatedClass"), [0, 0])
            d[1] += 1; d[0] += m.get("detected") == "true"
    def sc(items): 
        k = sum(v[0] for v in items); n = sum(v[1] for v in items); return 100.0 if n == 0 else 100.0 * k / n
    return sc(list(per.values())), {c.split("$")[0]: sc([v for cc, v in per.items() if cc.split("$")[0] == c.split("$")[0]]) for c in per}

def main(a):
    cmd = a[0]
    if cmd in ("summary", "gate"):
        ol, ob, nc, rows, fails = evaluate(a[1], a[2] if len(a) > 2 else None)
        if cmd == "summary":
            print("### Backend coverage (JaCoCo)\n")
            print(f"Overall: line {ol:.1f}% / branch {ob:.1f}%  (gate {CFG['coverage']['overall_min_line']}%, enabled from {CFG['coverage']['overall_enabled_from'] or 'not yet'})")
            print(f"New code: {'n/a (no executable changed lines)' if nc[0] is None else f'{nc[0]:.1f}% of {nc[1]} lines'}  (gate {CFG['coverage']['new_code_min']}%, enabled from {CFG['coverage']['new_code_enabled_from'] or 'not yet'})\n")
            print("| Safety/billing/iam class | classes | line | branch |\n|---|---|---|---|")
            for p, (n, l, b) in rows.items(): print(f"| {p} | {n} | {l:.1f}% | {b:.1f}% |")
            print("\n" + ("Failing gates: " + "; ".join(fails) if fails else "No enabled gate failed."))
        else:
            print("\n".join(fails)); sys.exit(1 if fails else 0)
    elif cmd == "baseline":
        ol, ob, nc, rows, _ = evaluate(a[1], None)
        json.dump({"recorded": today().isoformat(), "overall_line": round(ol, 1), "overall_branch": round(ob, 1),
                   "classes": {p: {"line": round(l, 1), "branch": round(b, 1)} for p, (n, l, b) in rows.items()}}, open(a[2], "w"), indent=2)
    elif cmd == "pit":
        total, per = pit_score(a[1]); p = CFG["pit"]; fails = []
        if total < p["min_score"]: fails.append(f"PIT score {total:.1f} < {p['min_score']}")
        for c, m in p["class_min_score"].items():
            s = per.get(c)
            if s is None: fails.append(f"{c}: no mutants found")
            elif s < m: fails.append(f"{c}: {s:.1f} < {m}")
        print(json.dumps({"score": round(total, 1), "classes": {k: round(v, 1) for k, v in per.items()}, "date": today().isoformat(), "failures": fails}))
        sys.exit(1 if fails else 0)
    elif cmd == "pit-pkg":
        out, fails = {}, []
        for name, pkg in CFG.get("pit_packages", {}).items():
            if name.startswith("_"): continue
            ms = ET.parse(a[1]).getroot().findall("mutation")
            mine = [m for m in ms if m.get("status") not in ("NON_VIABLE", "RUN_ERROR") and m.findtext("mutatedClass").split("$")[0] in pkg["classes"]]
            for c in pkg["classes"]:
                if not any(m.findtext("mutatedClass").split("$")[0] == c for m in mine): fails.append(f"{name}: {c}: no mutants found")
            score = 100.0 if not mine else 100.0 * sum(m.get("detected") == "true" for m in mine) / len(mine)
            out[name] = round(score, 1)
            if score < pkg["min_score"]: fails.append(f"{name}: PIT score {score:.1f} < {pkg['min_score']}")
        print(json.dumps({"packages": out, "failures": fails})); sys.exit(1 if fails else 0)
    elif cmd == "pit-drop":
        cur = json.load(open(a[1]))["score"]
        try: last = json.load(open(a[2]))["last_green"]["score"]
        except (FileNotFoundError, KeyError): print("no green history yet"); return
        d = last - cur
        print(f"last green {last}, now {cur}, drop {d:.1f}")
        sys.exit(1 if d > CFG["pit"]["max_drop_points"] else 0)
    else: sys.exit(__doc__)

if __name__ == "__main__": main(sys.argv[1:])
