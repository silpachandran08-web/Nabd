#!/usr/bin/env python3
"""PHL-021: authors the pharmacy golden packs, one per market (backend/src/test/resources/golden/phl-{in,sa,ae,qa}/cases.json).

Each case is a pharmacy cart: every line carries the raw tax class the billing head resolves to (`pharmacy.base`), the two inputs
pharmacy supplies (`pharmacy.priceInclusive`, `pharmacy.zeroRated`) and the cart line they must fold into. Expected figures come from
this file's own Decimal arithmetic (the independent oracle of gen_in_pack.py with a VAT split added), not from the Java engine.
The chartered accountant / VAT adviser reviews cases.json, then signs it with scripts/ci/golden_pack.py (Needs external action).
  python3 tools/golden/gen_phl_pack.py
"""
import importlib.util, json, os, sys
from decimal import Decimal as D

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
spec = importlib.util.spec_from_file_location("gen_in_pack", os.path.join(HERE, "gen_in_pack.py"))
g = importlib.util.module_from_spec(spec)
spec.loader.exec_module(g)

spec2 = importlib.util.spec_from_file_location("golden_pack", os.path.join(ROOT, "scripts", "ci", "golden_pack.py"))
gp = importlib.util.module_from_spec(spec2)
spec2.loader.exec_module(gp)

PACKS = {  # dir -> (id, jurisdiction, taxRegime, currency, version)
    "phl-in": ("PHL-IN", "IN", "GST", "INR", "PHL-IN-1"),
    "phl-sa": ("PHL-SA", "SA", "VAT", "SAR", "PHL-SA-1"),
    "phl-ae": ("PHL-AE", "AE", "VAT", "AED", "PHL-AE-1"),
    "phl-qa": ("PHL-QA", "QA", "VAT", "QAR", "PHL-QA-1"),
}


g.parts_gst = g.parts


def fold(base, price_inclusive, zero_rated):
    """The rule under test, written again here: zero-rated -> NIL 0%; a pharmacy inclusive flag applies to TAXABLE lines only."""
    cat, rate, incl, inter = base["category"], base["ratePercent"], base["inclusive"], base["interState"]
    if zero_rated:
        return "NIL", "0", False, False
    if price_inclusive is not None and cat == "TAXABLE":
        incl = price_inclusive
    return cat, rate, incl, inter


def P(key, gross, rate, qty=1, cat="TAXABLE", base_incl=False, inter=False, price_inclusive=None, zero_rated=False):
    base = {"category": cat, "ratePercent": str(rate), "inclusive": base_incl, "interState": inter}
    c, r, i, it = fold(base, price_inclusive, zero_rated)
    line = g.L(key, gross, c, r, True, i, it, qty)
    line["pharmacy"] = {"base": base, "priceInclusive": price_inclusive, "zeroRated": zero_rated}
    return line


def C(key, gross):  # a consultation line next to the medicines: exempt in India, taxable elsewhere is not modelled here
    return g.L(key, gross, "EXEMPT", "0")


def build(market):
    cases = []

    def add(title, lines, discount=0, bos=False, round_off=False):
        cid = f"PHL-{market.upper()}-{len(cases) + 1:03d}"
        c = g.cart(lines, discount, bos, round_off)
        exp = g.invoice(c)
        cases.append({"id": cid, "title": title, "kind": "invoice", "cart": c, "expected": exp, "printed": g.printed(c, exp)})

    if market == "in":
        mrps = [11200, 5550, 11800, 3350, 105000, 525, 25000, 99999, 1780, 8400]
        for n, mrp in enumerate(mrps):
            for rate in (5, 12, 18):
                if len(cases) >= 24:
                    break
                if (n + rate) % 2 == 0 or rate == 12:
                    add(f"MRP {mrp / 100:.2f} inclusive of {rate}% GST", [P("med", mrp, rate, price_inclusive=True)], round_off=True)
        add("Three strips at MRP 112.00, 12% inclusive", [P("med", 33600, 12, qty=3, price_inclusive=True)], round_off=True)
        add("Inter-state sale at MRP 112.00 gives IGST", [P("med", 11200, 12, inter=True, price_inclusive=True)], round_off=True)
        add("Invoice discount on an MRP-inclusive line", [P("med", 11200, 12, price_inclusive=True)], discount=1200, round_off=True)
        add("Consultation (exempt) plus two MRP-inclusive medicines", [C("consult", 50000), P("a", 11200, 12, price_inclusive=True), P("b", 5250, 5, price_inclusive=True)], round_off=True)
        add("Head says inclusive, pharmacy leaves it unset: the head stands", [P("med", 11200, 12, base_incl=True)], round_off=True)
        add("Pharmacy says exclusive on a head marked inclusive", [P("med", 10000, 12, base_incl=True, price_inclusive=False)], round_off=True)
        add("Exclusive price with GST added on top", [P("med", 10000, 12, price_inclusive=False)], round_off=True)
        add("Bill of Supply of an unregistered clinic carries no tax", [P("med", 11200, 12, price_inclusive=True)], bos=True, round_off=True)
        add("Nil-rated item stays nil", [P("med", 5000, 0, cat="NIL")], round_off=True)
    elif market == "sa":
        for n, gross in enumerate([1000, 2500, 4999, 10000, 12345, 333, 75000, 150, 9999, 20001]):
            add(f"Medicine at {gross / 100:.2f} SAR, VAT 15%", [P("med", gross, 15)])
            add(f"Zero-rated medicine at {gross / 100:.2f} SAR", [P("med", gross, 15, zero_rated=True)])
        add("Two units of a taxable medicine", [P("med", 5000, 15, qty=2)])
        add("Zero-rated and taxable medicine on one invoice", [P("a", 4000, 15, zero_rated=True), P("b", 6000, 15)])
        add("Invoice discount spread over a taxable and a zero-rated medicine", [P("a", 4000, 15, zero_rated=True), P("b", 6000, 15)], discount=1000)
        add("Tax-inclusive shelf price of 115.00 SAR", [P("med", 11500, 15, price_inclusive=True)])
    elif market == "ae":
        for gross in [1000, 2500, 4999, 10000, 12345, 333, 75000, 150, 9999, 20001, 525, 8800]:
            add(f"Medicine at {gross / 100:.2f} AED, VAT 5%", [P("med", gross, 5)])
        add("Tax-inclusive shelf price of 105.00 AED", [P("med", 10500, 5, price_inclusive=True)])
        add("Three units of a taxable medicine", [P("med", 3000, 5, qty=3)])
        add("Invoice discount on two medicines", [P("a", 4000, 5), P("b", 6000, 5)], discount=500)
        add("Exempt line next to a taxable medicine", [P("a", 4000, 0, cat="EXEMPT"), P("b", 6000, 5)])
        add("Pharmacy flag zero-rated overrides the head rate", [P("med", 4000, 5, zero_rated=True)])
        add("Tax-inclusive line with a discount", [P("med", 10500, 5, price_inclusive=True)], discount=1050)
        add("One fils above a round number", [P("med", 1001, 5)])
        add("Small amount rounds half up", [P("med", 190, 5)])
    elif market == "qa":
        for gross in [1000, 2500, 4999, 10000, 12345, 333, 75000, 150, 9999, 20001, 525, 8800]:
            add(f"Medicine at {gross / 100:.2f} QAR, no VAT", [P("med", gross, 0, cat="NIL")])
        add("Two medicines, no VAT line", [P("a", 4000, 0, cat="NIL"), P("b", 6000, 0, cat="NIL")])
        add("Three units of one medicine", [P("med", 3000, 0, qty=3, cat="NIL")])
        add("Invoice discount, still no VAT", [P("a", 4000, 0, cat="NIL"), P("b", 6000, 0, cat="NIL")], discount=500)
        add("Zero-rated flag on a nil head", [P("med", 4000, 0, cat="NIL", zero_rated=True)])
        add("Exempt head", [P("med", 4000, 0, cat="EXEMPT")])
        add("Price inclusive flag has nothing to back out at 0%", [P("med", 4000, 0, cat="NIL", price_inclusive=True)])
        add("Whole-number total", [P("med", 10000, 0, cat="NIL")])
        add("Large basket of four lines", [P("a", 1250, 0, cat="NIL"), P("b", 3333, 0, cat="NIL"), P("c", 99, 0, cat="NIL"), P("d", 70000, 0, cat="NIL")])
    return cases


def main():
    for d, (pid, jur, regime, cur, ver) in PACKS.items():
        g.parts = g.parts_gst if regime == "GST" else (lambda line: [("VAT", D(line["ratePercent"]))])  # the jurisdiction split of the oracle
        cases = build(d.split("-")[1])
        assert len(cases) >= 20, (d, len(cases))
        for c in cases:
            c["review"] = {"ruleSetVersion": ver, "reviewer": None, "signedOn": None}
        doc = {"pack": {"id": pid, "jurisdiction": jur, "taxRegime": regime, "currency": cur, "minorUnits": 2, "ruleSetVersion": ver, "minCases": 20},
               "cases": cases}
        out = os.path.join(ROOT, "backend", "src", "test", "resources", "golden", d)
        os.makedirs(out, exist_ok=True)
        gp.save(os.path.join(out, "cases.json"), doc)
        gp.save(os.path.join(out, "signoff.json"), {
            "packId": pid, "ruleSetVersion": ver, "status": "pending-ca-review", "ruleSetSha256": gp.rules_hash(ROOT),
            "casesSha256": gp.cases_hash(doc), "reviewer": None, "membershipNo": None, "signedOn": None, "signatureFile": None,
            "signatureSha256": None})
        print(f"{d}: {len(cases)} cases")


if __name__ == "__main__":
    main()
