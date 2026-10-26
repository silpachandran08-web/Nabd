#!/usr/bin/env python3
"""FIN-010: authors the India GST golden pack (backend/src/test/resources/golden/in/cases.json).

The expected outputs below come from this file's own arithmetic (python Decimal, written independently of the Java engine), not
from running the Java engine. The chartered accountant reviews cases.json (inputs and expected figures), then signs it with
scripts/ci/golden_pack.py. Re-run this script only to change or add cases; then re-sign.
  python3 tools/golden/gen_in_pack.py [--out PATH]
"""
import json, sys
from decimal import Decimal as D, ROUND_HALF_UP, ROUND_FLOOR

VERSION = "IN-GST-1"


def hu(x):  # half-up to an integer number of minor units
    return int(D(x).quantize(D(1), rounding=ROUND_HALF_UP))


def L(key, gross, cat="TAXABLE", rate="18", disc=True, incl=False, inter=False, qty=1):
    return {"key": key, "quantity": qty, "grossMinor": gross, "category": cat, "ratePercent": rate, "discountable": disc,
            "inclusive": incl, "interState": inter}


def cart(lines, discount=0, bos=False, round_off=True):
    return {"minorUnits": 2, "wholeUnitRoundOff": round_off, "billOfSupply": bos, "invoiceDiscountMinor": discount, "lines": lines}


def allocate(amount, weights):
    total = sum(weights)
    out = [0] * len(weights)
    if amount == 0 or total == 0:
        return out
    rem = []
    for i, w in enumerate(weights):
        out[i], r = divmod(amount * w, total)
        rem.append(r)
    left = amount - sum(out)
    order = sorted([i for i, w in enumerate(weights) if w > 0], key=lambda i: (-rem[i], i))
    for k in range(left):
        out[order[k]] += 1
    return out


def parts(line):
    rate = D(line["ratePercent"])
    if line["interState"]:
        return [("IGST", rate)]
    half = (rate / 2).quantize(D("0.01"), rounding=ROUND_HALF_UP)
    return [("CGST", half), ("SGST", rate - half)]


def comp(t, rate, taxable, amount):
    return {"type": t, "ratePercent": f"{D(rate):.2f}", "taxableMinor": taxable, "amountMinor": amount}


def invoice(c):
    ls = c["lines"]
    w = [l["grossMinor"] if l["discountable"] else 0 for l in ls]
    assert 0 <= c["invoiceDiscountMinor"] <= sum(w)
    alloc = allocate(c["invoiceDiscountMinor"], w)
    out, sub, tax, raw = [], 0, 0, 0
    for l, a in zip(ls, alloc):
        net = l["grossMinor"] - a
        taxable, comps = net, []
        if not c["billOfSupply"] and l["category"] == "TAXABLE" and D(l["ratePercent"]) > 0:
            ps = parts(l)
            if l["inclusive"]:
                taxable = hu(D(net) * 100 / (100 + D(l["ratePercent"])))
                left = net - taxable
                for i, (t, r) in enumerate(ps):
                    amt = left if i == len(ps) - 1 else min(left, hu(D(taxable) * r / 100))
                    left -= amt
                    comps.append(comp(t, r, taxable, amt))
            else:
                comps = [comp(t, r, taxable, hu(D(taxable) * r / 100)) for t, r in ps]
        t_amt = sum(x["amountMinor"] for x in comps)
        out.append({"key": l["key"], "discountMinor": a, "taxableMinor": taxable, "taxes": comps})
        sub += taxable + a
        tax += t_amt
        raw += taxable + t_amt
    total = raw
    if c["wholeUnitRoundOff"]:
        total = int((D(raw + 50) / 100).to_integral_value(rounding=ROUND_FLOOR)) * 100
    return {"lines": out, "subtotalMinor": sub, "discountMinor": c["invoiceDiscountMinor"], "taxMinor": tax,
            "roundOffMinor": total - raw, "totalMinor": total}


def printed(c, exp):
    """Printed fields: document title and the per-rate tax summary (taxable + tax per component type and rate)."""
    summary = {}
    for lr in exp["lines"]:
        for x in lr["taxes"]:
            k = (x["type"], x["ratePercent"])
            s = summary.setdefault(k, [0, 0])
            s[0] += x["taxableMinor"]
            s[1] += x["amountMinor"]
    return {"documentTitle": "Bill of Supply" if c["billOfSupply"] else "Tax Invoice",
            "taxSummary": [{"type": k[0], "ratePercent": k[1], "taxableMinor": v[0], "taxMinor": v[1]} for k, v in sorted(summary.items())],
            "roundOffLine": exp["roundOffMinor"] != 0}


# ---- credit notes: reverse against what is left of each line (python mirror of the rule, written for this oracle)
def remaining(c, exp):
    out = []
    for l, r in zip(c["lines"], exp["lines"]):
        out.append({"key": l["key"], "qty": l["quantity"], "taxable": r["taxableMinor"],
                    "comps": [dict(x) for x in r["taxes"]]})
    return out


def comp_total(rem):
    return sum(x["amountMinor"] for x in rem["comps"])


def reverse_qty(rem, q):
    assert 1 <= q <= rem["qty"]
    if q == rem["qty"]:
        return {"key": rem["key"], "quantity": q, "taxableMinor": rem["taxable"], "taxes": [dict(x) for x in rem["comps"]]}
    f = lambda v: hu(D(v) * q / rem["qty"])
    return {"key": rem["key"], "quantity": q, "taxableMinor": f(rem["taxable"]),
            "taxes": [comp(x["type"], x["ratePercent"], f(x["taxableMinor"]), f(x["amountMinor"])) for x in rem["comps"]]}


def reverse_amount(rems, amount, roundoff_left):
    gross = [r["taxable"] + comp_total(r) for r in rems]
    ro = 0
    if amount == sum(gross) + roundoff_left:
        ro, amount = roundoff_left, sum(gross)
    assert 1 <= amount <= sum(gross)
    part = allocate(amount, gross)
    lines = []
    for r, g, p in zip(rems, gross, part):
        if p == 0:
            continue
        if p == g:
            lines.append({"key": r["key"], "quantity": 0, "taxableMinor": r["taxable"], "taxes": [dict(x) for x in r["comps"]]})
            continue
        taxable = hu(D(r["taxable"]) * p / g)
        ca = allocate(p - taxable, [x["amountMinor"] for x in r["comps"]])
        cs = [comp(x["type"], x["ratePercent"], taxable, a) for x, a in zip(r["comps"], ca)]
        lines.append({"key": r["key"], "quantity": 0, "taxableMinor": taxable if cs else p, "taxes": cs})
    return lines, ro


def apply(rem, rev):
    rem["qty"] -= rev["quantity"]
    rem["taxable"] -= rev["taxableMinor"]
    for x in rem["comps"]:
        for y in rev["taxes"]:
            if x["type"] == y["type"]:
                x["taxableMinor"] -= y["taxableMinor"]
                x["amountMinor"] -= y["amountMinor"]


def credit_notes(c, select_list):
    exp = invoice(c)
    rems = remaining(c, exp)
    ro_left = exp["roundOffMinor"]
    notes = []
    for sel in select_list:
        by = {r["key"]: r for r in rems}
        if "lines" in sel:
            revs = [reverse_qty(by[k], q) for k, q in sel["lines"]]
            done = all(
                sum(v["taxableMinor"] for v in revs if v["key"] == r["key"]) == r["taxable"] and
                sum(x["amountMinor"] for v in revs if v["key"] == r["key"] for x in v["taxes"]) == comp_total(r) for r in rems)
            ro = ro_left if done else 0
        else:
            revs, ro = reverse_amount(rems, sel["amountMinor"], ro_left)
        for v in revs:
            apply(by[v["key"]], v)
        ro_left -= ro
        taxable = sum(v["taxableMinor"] for v in revs)
        tax = sum(x["amountMinor"] for v in revs for x in v["taxes"])
        notes.append({"lines": revs, "taxableMinor": taxable, "taxMinor": tax, "roundOffMinor": ro, "totalMinor": taxable + tax + ro})
    return notes


CASES = []


def inv_case(cid, title, c, **extra):
    exp = invoice(c)
    CASES.append({"id": cid, "title": title, "kind": "invoice", "cart": c, "expected": exp, "printed": printed(c, exp), **extra})


def cn_case(cid, title, c, selects):
    notes = credit_notes(c, selects)
    exp = invoice(c)
    CASES.append({"id": cid, "title": title, "kind": "credit_note", "cart": c, "invoiceTotalMinor": exp["totalMinor"],
                  "reversals": [({"lines": [{"key": k, "quantity": q} for k, q in s["lines"]]} if "lines" in s else {"amountMinor": s["amountMinor"]}) for s in selects],
                  "expected": notes})


def checkpoints_case(cid, title, carts):
    CASES.append({"id": cid, "title": title, "kind": "checkpoints",
                  "checkpoints": [{"cart": c, "expected": invoice(c), "printed": printed(c, invoice(c))} for c in carts]})


# ---------------------------------------------------------------- invoices
inv_case("IN-001", "Consultation only, exempt", cart([L("consult", 50000, "EXEMPT", "0")]))
inv_case("IN-002", "Consultation exempt plus 18% procedure", cart([L("consult", 50000, "EXEMPT", "0"), L("proc", 100000)]))
inv_case("IN-003", "Cosmetic service at 18%", cart([L("cosmetic", 250000)]))
inv_case("IN-004", "Medicine at 5%", cart([L("med5", 12000, rate="5")]))
inv_case("IN-005", "Medicine at 12%", cart([L("med12", 8550, rate="12")]))
inv_case("IN-006", "Medicines at 5% and 12% together", cart([L("med5", 12000, rate="5"), L("med12", 8550, rate="12")]))
inv_case("IN-007", "Invoice discount on one 18% line (QA-13)", cart([L("proc", 100000)], 10000))
inv_case("IN-008", "Invoice discount shared pro rata over exempt, 18% and 5% lines", cart([L("consult", 50000, "EXEMPT", "0"), L("proc", 100000), L("med", 20000, rate="5")], 17000))
inv_case("IN-009", "Discount of 1.00 over three equal lines: largest remainder", cart([L("a", 10000), L("b", 10000), L("c", 10000)], 100))
inv_case("IN-010", "Line discount: only the discountable line takes the discount", cart([L("proc", 80000), L("med", 12000, rate="5", disc=False)], 8000))
inv_case("IN-011", "Inter-state B2B supply at 18% (IGST)", cart([L("proc", 100000, inter=True)]))
inv_case("IN-012", "Inter-state supply at 5% (IGST)", cart([L("med", 10000, rate="5", inter=True)]))
inv_case("IN-013", "Inter-state supply with invoice discount", cart([L("proc", 100000, inter=True), L("med", 20000, rate="12", inter=True)], 6000))
inv_case("IN-014", "Unregistered clinic: Bill of Supply, no tax lines, with discount", cart([L("proc", 100000), L("med", 12000, rate="5")], 2000, bos=True))
inv_case("IN-015", "Unregistered clinic: Bill of Supply with an exempt line", cart([L("consult", 50000, "EXEMPT", "0"), L("proc", 30000)], 0, bos=True))
inv_case("IN-016", "Package bundle (WE-01 style): exempt consults plus 18% sessions at allocated prices", cart([L("pkg-consult", 120000, "EXEMPT", "0", qty=3), L("pkg-session", 480000, qty=6)]))
inv_case("IN-017", "Tax-inclusive price at 18%", cart([L("proc", 118000, incl=True)]))
inv_case("IN-018", "Tax-inclusive price at 5%", cart([L("med", 10500, rate="5", incl=True)]))
inv_case("IN-019", "Tax-inclusive price at 12% with an awkward amount", cart([L("med", 99900, rate="12", incl=True)]))
inv_case("IN-020", "Round-off: total rounds up at .50", cart([L("x", 33350, "NIL", "0")]))
inv_case("IN-021", "Round-off: total rounds down below .50", cart([L("x", 33333, "NIL", "0")]))
inv_case("IN-022", "Round-off: whole-rupee total, no round-off line", cart([L("x", 33300, "NIL", "0")]))
inv_case("IN-023", "Half-paisa tax: 9% of 33.33 rounds half-up per component", cart([L("x", 3333)]))
inv_case("IN-024", "Half-paisa tax at 5%: 2.5% of 10.50 per component", cart([L("x", 1050, rate="5")]))
inv_case("IN-025", "Free item (zero value)", cart([L("proc", 100000), L("free", 0, "NIL", "0")]))
inv_case("IN-026", "Nil-rated line", cart([L("nil", 75000, "NIL", "0")]))
inv_case("IN-027", "Large bill at 18%", cart([L("big", 12500000)]))
inv_case("IN-028", "Five lines, mixed rates, with discount", cart([
    L("consult", 60000, "EXEMPT", "0"), L("xray", 90000), L("med5", 4550, rate="5"), L("med12", 15075, rate="12"), L("cosmetic", 220000)], 25000))
inv_case("IN-029", "Whole-unit round-off off (exact paise total)", cart([L("proc", 99999)], round_off=False))
inv_case("IN-030", "Discount equal to the whole discountable value", cart([L("proc", 100000), L("consult", 50000, "EXEMPT", "0", disc=False)], 100000))

# ---------------------------------------------------------------- credit notes
full = cart([L("proc", 100000)])
cn_case("IN-031", "Full refund of INR 1,180 (1,000 + 18%): CGST 90.00 + SGST 90.00 reversed", full, [{"lines": [("proc", 1)]}])
three = cart([L("a", 100000), L("b", 50000, rate="5"), L("c", 20000, "NIL", "0")])
cn_case("IN-032", "Partial refund of 1 of 3 lines", three, [{"lines": [("b", 1)]}])
qty3 = cart([L("sess", 300000, qty=3)])
cn_case("IN-033", "Refund of 1 of 3 sessions of one line", qty3, [{"lines": [("sess", 1)]}])
cn_case("IN-034", "Goodwill refund of 100.00 on a 1,180.00 invoice", full, [{"amountMinor": 10000}])
cn_case("IN-035", "Goodwill refund spread over two taxed lines", cart([L("a", 100000), L("b", 50000, rate="5")]), [{"amountMinor": 70000}])
cn_case("IN-036", "Two credit notes that together reverse the invoice exactly (1 then 2 of 3 sessions)", qty3, [{"lines": [("sess", 1)]}, {"lines": [("sess", 2)]}])
cn_case("IN-037", "Credit note on a discounted invoice reverses the discounted value", cart([L("a", 100000), L("b", 100000)], 20000), [{"lines": [("a", 1)]}])
cn_case("IN-038", "Credit note on an inter-state invoice reverses IGST", cart([L("proc", 100000, inter=True)]), [{"lines": [("proc", 1)]}])
cn_case("IN-039", "Full refund of an invoice with round-off returns the round-off", cart([L("a", 33333)]), [{"lines": [("a", 1)]}])
cn_case("IN-040", "Credit note on an exempt line has no tax", cart([L("consult", 50000, "EXEMPT", "0"), L("proc", 100000)]), [{"lines": [("consult", 1)]}])
cn_case("IN-041", "Credit note on a tax-inclusive line", cart([L("proc", 118000, incl=True)]), [{"lines": [("proc", 1)]}])
cn_case("IN-042", "Credit note on a Bill of Supply has no tax", cart([L("proc", 100000)], 0, bos=True), [{"lines": [("proc", 1)]}])
cn_case("IN-043", "Goodwill refund then the rest by amount: parts add up to the invoice", full, [{"amountMinor": 10000}, {"amountMinor": 108000}])
cn_case("IN-044", "Refund of 2 of 3 sessions then a goodwill amount for the rest", qty3, [{"lines": [("sess", 2)]}, {"amountMinor": 118000}])

# ---------------------------------------------------------------- interim + final checkpoint
interim = cart([L("consult", 40000, "EXEMPT", "0")])
final = cart([L("consult", 40000, "EXEMPT", "0"), L("proc", 120000)])
checkpoints_case("IN-045", "Interim consultation bill, then the procedure is appended at the final checkpoint", [interim, final])
checkpoints_case("IN-046", "Interim bill, then final checkpoint with an invoice discount", [interim, cart([L("consult", 40000, "EXEMPT", "0"), L("proc", 120000)], 16000)])


def main():
    out = "backend/src/test/resources/golden/in/cases.json"
    if "--out" in sys.argv:
        out = sys.argv[sys.argv.index("--out") + 1]
    for c in CASES:
        c["review"] = {"ruleSetVersion": VERSION, "reviewer": None, "signedOn": None}
    pack = {"pack": {"id": "IN-GST", "jurisdiction": "IN", "taxRegime": "GST", "currency": "INR", "minorUnits": 2,
                     "ruleSetVersion": VERSION, "minCases": 40}, "cases": CASES}
    with open(out, "w") as f:
        json.dump(pack, f, indent=1)
        f.write("\n")
    print(f"{len(CASES)} cases -> {out}")


if __name__ == "__main__":
    main()
