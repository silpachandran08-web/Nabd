#!/usr/bin/env python3
"""FIN-010 golden-pack sign-off gate.

  golden_pack.py check [--root DIR] [--require-signed]   fail when a rule source or a case changed without a re-sign
  golden_pack.py rehash [--root DIR]                     author step: record the new hashes, status back to pending-ca-review
  golden_pack.py sign --pack ID --reviewer NAME --membership NO --date YYYY-MM-DD --signature-file PATH [--root DIR]
                                                         record the chartered accountant's sign-off (after the CA reviewed cases.json)

Each pack lives in backend/src/test/resources/golden/<dir>/ with cases.json and signoff.json. signoff.json holds the sha256 of the
rule sources the pack was written against (tax engine sources, CreditNoteMath, every migration that mentions tax_rules) and of the
case content without its review blocks. Changing either without running rehash/sign is "golden pack not re-signed" and fails CI;
rehash after a rule change drops the pack back to pending-ca-review, so a release (check --require-signed) is blocked until the CA signs again.
Needs external action: the CA's review itself and the signature file.
"""
import argparse, datetime, glob, hashlib, json, os, re, sys

PACKS = "backend/src/test/resources/golden"
RULE_FILES = [
    "backend/src/main/java/com/nabd/hms/billing/tax/TaxEngine.java",
    "backend/src/main/java/com/nabd/hms/billing/tax/RuleBasedTaxEngine.java",
    "backend/src/main/java/com/nabd/hms/billing/tax/IndiaGst.java",
    "backend/src/main/java/com/nabd/hms/billing/tax/FlatVat.java",
    "backend/src/main/java/com/nabd/hms/billing/tax/JurisdictionRules.java",
    "backend/src/main/java/com/nabd/hms/billing/tax/PlaceOfSupply.java",
    "backend/src/main/java/com/nabd/hms/billing/CreditNoteMath.java",
]


def sha(b):
    return hashlib.sha256(b).hexdigest()


def rule_sources(root):
    files = [f for f in RULE_FILES if os.path.exists(os.path.join(root, f))]
    for m in sorted(glob.glob(os.path.join(root, "db/migrations/*.sql"))):
        if re.search(r"tax_rules", open(m, encoding="utf-8").read(), re.I):
            files.append(os.path.relpath(m, root))
    return sorted(files)


def rules_hash(root):
    h = hashlib.sha256()
    for f in rule_sources(root):
        h.update(f.encode() + b"\0" + sha(open(os.path.join(root, f), "rb").read()).encode() + b"\n")
    return h.hexdigest()


def cases_hash(doc):
    clean = json.loads(json.dumps(doc))
    for c in clean["cases"]:
        c.pop("review", None)
    return sha(json.dumps(clean, sort_keys=True, separators=(",", ":")).encode())


def packs(root):
    return sorted(os.path.dirname(p) for p in glob.glob(os.path.join(root, PACKS, "*", "cases.json")))


def load(d):
    return json.load(open(os.path.join(d, "cases.json"))), json.load(open(os.path.join(d, "signoff.json")))


def save(path, doc):
    with open(path, "w") as f:
        json.dump(doc, f, indent=1)
        f.write("\n")


def problems_of(root, require_signed):
    out, warn = [], []
    rh = rules_hash(root)
    for d in packs(root):
        name = os.path.basename(d)
        if not os.path.exists(os.path.join(d, "signoff.json")):
            out.append(f"{name}: signoff.json missing"); continue
        doc, so = load(d)
        meta = doc["pack"]
        if len(doc["cases"]) < meta["minCases"]:
            out.append(f"{name}: {len(doc['cases'])} cases, the pack needs at least {meta['minCases']}")
        for c in doc["cases"]:
            r = c.get("review") or {}
            if r.get("ruleSetVersion") != meta["ruleSetVersion"] or "reviewer" not in r or "signedOn" not in r:
                out.append(f"{name}/{c.get('id')}: review block must record ruleSetVersion {meta['ruleSetVersion']}, reviewer and signedOn")
        if so.get("ruleSetVersion") != meta["ruleSetVersion"]:
            out.append(f"{name}: signoff rule-set version {so.get('ruleSetVersion')} differs from the pack's {meta['ruleSetVersion']}")
        if so.get("ruleSetSha256") != rh:
            out.append(f"{name}: golden pack not re-signed: the tax rules or engine changed since it was last signed (run golden_pack.py rehash, then have the CA review and sign)")
        if so.get("casesSha256") != cases_hash(doc):
            out.append(f"{name}: golden pack not re-signed: cases.json changed since it was last signed")
        if so.get("status") == "signed":
            for k in ("reviewer", "membershipNo", "signedOn", "signatureFile"):
                if not so.get(k):
                    out.append(f"{name}: signed pack lacks {k}")
            try:
                datetime.date.fromisoformat(so.get("signedOn") or "")
            except ValueError:
                out.append(f"{name}: signedOn is not an ISO date")
            sf = os.path.join(root, so.get("signatureFile") or "-")
            if not os.path.isfile(sf):
                out.append(f"{name}: signature file {so.get('signatureFile')} not in the repository")
            elif sha(open(sf, "rb").read()) != so.get("signatureSha256"):
                out.append(f"{name}: signature file does not match signatureSha256")
            for c in doc["cases"]:
                r = c["review"]
                if r["reviewer"] != so.get("reviewer") or r["signedOn"] != so.get("signedOn"):
                    out.append(f"{name}/{c['id']}: review differs from the pack sign-off")
        elif so.get("status") == "pending-ca-review":
            (out if require_signed else warn).append(f"{name}: pending CA review (Needs external action: the chartered accountant has not signed this pack)")
        else:
            out.append(f"{name}: unknown signoff status {so.get('status')}")
    if not packs(root):
        out.append("no golden pack found")
    return out, warn


def cmd_check(a):
    out, warn = problems_of(a.root, a.require_signed)
    for w in warn:
        print("WARNING", w)
    for p in out:
        print("FAIL", p)
    print(f"golden-pack: {len(packs(a.root))} pack(s), {len(out)} problem(s)")
    return 1 if out else 0


def cmd_rehash(a):
    for d in packs(a.root):
        doc, so = load(d)
        for c in doc["cases"]:
            c["review"] = {"ruleSetVersion": doc["pack"]["ruleSetVersion"], "reviewer": None, "signedOn": None}
        save(os.path.join(d, "cases.json"), doc)
        save(os.path.join(d, "signoff.json"), {
            "packId": doc["pack"]["id"], "ruleSetVersion": doc["pack"]["ruleSetVersion"], "status": "pending-ca-review",
            "ruleSetSha256": rules_hash(a.root), "casesSha256": cases_hash(doc), "reviewer": None, "membershipNo": None,
            "signedOn": None, "signatureFile": None, "signatureSha256": None})
        print(f"{os.path.basename(d)}: hashes recorded, status pending-ca-review")
    return 0


def cmd_sign(a):
    for d in packs(a.root):
        doc, so = load(d)
        if doc["pack"]["id"] != a.pack:
            continue
        datetime.date.fromisoformat(a.date)
        sf = os.path.join(a.root, a.signature_file)
        if not os.path.isfile(sf):
            print(f"signature file {a.signature_file} not found"); return 1
        out, _ = problems_of(a.root, False)
        if any("not re-signed" in p for p in out):
            print("hashes are stale: run rehash first"); return 1
        for c in doc["cases"]:
            c["review"] = {"ruleSetVersion": doc["pack"]["ruleSetVersion"], "reviewer": a.reviewer, "signedOn": a.date}
        save(os.path.join(d, "cases.json"), doc)
        so.update({"status": "signed", "reviewer": a.reviewer, "membershipNo": a.membership, "signedOn": a.date,
                   "signatureFile": a.signature_file, "signatureSha256": sha(open(sf, "rb").read()),
                   "ruleSetSha256": rules_hash(a.root), "casesSha256": cases_hash(doc)})
        save(os.path.join(d, "signoff.json"), so)
        print(f"{a.pack}: signed by {a.reviewer} on {a.date}")
        return 0
    print(f"pack {a.pack} not found"); return 1


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for n in ("check", "rehash", "sign"):
        p = sub.add_parser(n)
        p.add_argument("--root", default=".")
        if n == "check":
            p.add_argument("--require-signed", action="store_true")
        if n == "sign":
            for k in ("pack", "reviewer", "membership", "date", "signature-file"):
                p.add_argument("--" + k, required=True)
    a = ap.parse_args(argv)
    return {"check": cmd_check, "rehash": cmd_rehash, "sign": cmd_sign}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
