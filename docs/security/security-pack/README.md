# Security pack for enterprise buyers (PLT-053)

The pack is what a clinic group or hospital security team receives on request. This index lists what belongs in it. Binary documents (reports, letters, certificates) are held in the access-controlled document store, not in git; the register below records the date and the location reference only.

| Item | Source | Status |
|---|---|---|
| Penetration test scope and rules of engagement | `docs/security/pentest-scope.md` (signed copy in the document store) | Draft; signature Needs external action |
| Vendor report summary (executive summary only) | Vendor | Not yet received |
| Retest letter | Vendor | Not yet received |
| Findings register (severity, status, retest result; no exploit detail) | `docs/security/pentest-findings.csv` | Empty until the test runs |
| Data classification | `docs/security/data-classification.md` | Available |
| Threat models | `docs/security/threat-models/` | Available |
| Controls matrix | `docs/security/controls.yaml` | Available |

## Retest letter record
Fill in when the letter arrives. `scripts/ci/pentest_pack.py ga-gate` reads these two lines.

retest_letter_date: none
retest_letter_ref: none
