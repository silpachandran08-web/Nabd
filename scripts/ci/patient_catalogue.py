#!/usr/bin/env python3
"""FEF-041: the patient language catalogue (en, hi, kn, te) as one versioned artefact.

Sources: frontend/packages/i18n/messages/<lang>/patient.json (patient-web), the WhatsApp template catalogue rows in
db/migrations/*.sql (template_catalogue) and frontend/packages/i18n/catalogue/review.json (clinical keys and native-clinician reviews).
Output: patient-catalogue.json, written to the i18n package and copied into comms-gateway resources so the Java service renders the
same text and records the same version per send.

  patient_catalogue.py build            write both copies
  patient_catalogue.py check            completeness (100% of keys in en/hi/kn/te), review metadata, copies up to date
  patient_catalogue.py check --release  also fail while any clinical key is not reviewed in hi, kn and te

A string is 'reviewed' only when review.json holds an entry with a reviewer id, a date and the sha256 of the exact text reviewed;
editing a reviewed string makes its review stale. Machine drafts are never reviewed.
"""
import datetime
import hashlib
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
I18N = ROOT / "frontend/packages/i18n"
REVIEW = I18N / "catalogue/review.json"
COPIES = [I18N / "catalogue/patient-catalogue.json", ROOT / "services/comms-gateway/src/main/resources/i18n/patient-catalogue.json"]
LOCALES = ["en", "hi", "kn", "te"]
REVIEWED_LOCALES = LOCALES[1:]
ROW = re.compile(r"\('([a-z0-9_]+)','(en|hi|kn|te|ar)','(UTILITY|AUTHENTICATION)','((?:[^']|'')*)'")
PLACEHOLDER = re.compile(r"\{[A-Za-z0-9_]+\}")
SLOT = re.compile(r"\{\{\d+}}")


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def load_patient(root=ROOT):
    base = root / "frontend/packages/i18n/messages"
    return {l: json.loads((base / l / "patient.json").read_text(encoding="utf-8")) for l in LOCALES}


def load_templates(root=ROOT):
    out = {}
    for f in sorted((root / "db/migrations").glob("*.sql")):
        text = f.read_text(encoding="utf-8")
        if "INSERT INTO template_catalogue" not in text:
            continue
        for key, lang, category, body in ROW.findall(text):
            out.setdefault(key, {})[lang] = {"body": body.replace("''", "'"), "category": category}
    return out


def entries(patient, templates):
    """(namespace, key, locale) -> text, for hi/kn/te (the languages that need review)."""
    for l in REVIEWED_LOCALES:
        for k, v in patient[l].items():
            yield "patient", k, l, v
        for k, by in templates.items():
            if l in by:
                yield "templates", k, l, by[l]["body"]


def review_state(review, patient, templates):
    """Valid reviews by (locale, ns, key) plus the problems found in review.json."""
    problems, ok = [], {}
    clinical = review.get("clinicalKeys", {})
    text_of = {(l, ns, k): t for ns, k, l, t in entries(patient, templates)}
    for r in review.get("reviews", []):
        where = f"review {r.get('locale')}/{r.get('ns')}/{r.get('key')}"
        id_ = (r.get("locale"), r.get("ns"), r.get("key"))
        if id_ not in text_of:
            problems.append(f"{where}: no such string in hi/kn/te")
            continue
        if not str(r.get("reviewerId", "")).strip():
            problems.append(f"{where}: reviewerId missing")
            continue
        try:
            on = datetime.date.fromisoformat(r.get("reviewedOn", ""))
        except ValueError:
            problems.append(f"{where}: reviewedOn must be an ISO date")
            continue
        if on > datetime.date.today():
            problems.append(f"{where}: reviewedOn is in the future")
            continue
        if r.get("textSha") != sha(text_of[id_]):
            problems.append(f"{where}: text changed after review (stale), review again")
            continue
        ok[id_] = {"status": "reviewed", "reviewerId": r["reviewerId"], "reviewedOn": r["reviewedOn"]}
    for ns, keys in clinical.items():
        known = set(patient["en"]) if ns == "patient" else set(templates)
        problems += [f"clinicalKeys.{ns}: unknown key {k}" for k in keys if k not in known]
    return ok, problems


def build(root=ROOT):
    patient, templates = load_patient(root), load_templates(root)
    review = json.loads((root / "frontend/packages/i18n/catalogue/review.json").read_text(encoding="utf-8"))
    ok, _ = review_state(review, patient, templates)
    states = {l: {"patient": {}, "templates": {}} for l in REVIEWED_LOCALES}
    for ns, k, l, _t in entries(patient, templates):
        states[l][ns][k] = ok.get((l, ns, k), {"status": "unreviewed"})
    body = {"schemaVersion": 1, "locales": LOCALES, "patient": patient, "templates": templates,
            "clinicalKeys": review.get("clinicalKeys", {}), "review": states}
    canonical = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return {"version": sha(canonical)[:12], **body}


def render(artefact):
    return json.dumps(artefact, ensure_ascii=False, sort_keys=True, indent=1) + "\n"


def check(root=ROOT, release=False, copies=None):
    patient, templates = load_patient(root), load_templates(root)
    problems = []
    en = patient["en"]
    for l in REVIEWED_LOCALES:
        problems += [f"patient/{l}: missing {k}" for k in en if not str(patient[l].get(k, "")).strip()]
        problems += [f"patient/{l}: extra key {k}" for k in patient[l] if k not in en]
        problems += [f"patient/{l}/{k}: placeholders differ from en" for k in en if k in patient[l]
                     and sorted(PLACEHOLDER.findall(en[k])) != sorted(PLACEHOLDER.findall(patient[l][k]))]
    for k, by in sorted(templates.items()):
        for l in LOCALES:
            if l not in by:
                problems.append(f"templates/{k}: missing {l}")
            elif "en" in by and sorted(SLOT.findall(by[l]["body"])) != sorted(SLOT.findall(by["en"]["body"])):
                problems.append(f"templates/{k}/{l}: {{{{n}}}} slots differ from en")
    review = json.loads((root / "frontend/packages/i18n/catalogue/review.json").read_text(encoding="utf-8"))
    ok, review_problems = review_state(review, patient, templates)
    problems += review_problems
    unreviewed = [f"{l}/{ns}/{k}" for ns, keys in review.get("clinicalKeys", {}).items() for k in keys for l in REVIEWED_LOCALES
                  if (l, ns, k) not in ok]
    if release:
        problems += [f"clinical key not reviewed by a native clinician: {u}" for u in unreviewed]
    fresh = render(build(root))
    for c in copies or [root / c.relative_to(ROOT) for c in COPIES]:
        if not c.exists() or c.read_text(encoding="utf-8") != fresh:
            problems.append(f"{c.relative_to(root)} is out of date: run python3 scripts/ci/patient_catalogue.py build")
    return problems, unreviewed


def main(argv):
    cmd = argv[1] if len(argv) > 1 else "check"
    if cmd == "build":
        text = render(build())
        for c in COPIES:
            c.parent.mkdir(parents=True, exist_ok=True)
            c.write_text(text, encoding="utf-8")
        print("wrote", *[str(c.relative_to(ROOT)) for c in COPIES], "version", json.loads(text)["version"])
        return 0
    problems, unreviewed = check(release="--release" in argv)
    for p in problems:
        print("FAIL", p)
    print(f"{len(unreviewed)} clinical string(s) unreviewed (hi/kn/te); all hi/kn/te text without a review entry is a machine draft")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
