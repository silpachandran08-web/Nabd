# India drug knowledge base: licence obligations (CLN-018)

Status: NOT SIGNED. This file records what the licence must cover and, once signed, what it says. No licence terms,
prices, vendor names or clause numbers are written here until the signed document exists (procurement owner fills the
"Signed value" column from the contract; no secrets, no contract text copied in). Target: signed by S5.
If it is not signed by S5, MF-51 stays on the CSO dictionary (CLN-004) and T-226 slips.

| # | Obligation (required by the story) | Signed value | How the product honours it | Where it is checked |
|---|---|---|---|---|
| 1 | Per-cell copies of the KB allowed (each regional cell) | pending | KB sits only in the cell DB and the Clinical Intelligence service | procurement review |
| 2 | Use inside the CDS service allowed | pending | drug master, brand-generic map, interaction checks (CLN-019, AI-01) | procurement review |
| 3 | Tenant count covered for 24 months | pending | plan number comes from the commercial forecast | procurement review |
| 4 | Redistribution: no bulk KB content to clients | pending | `GET /v1/clinical/drugs/search` returns names from the tenant's own data only, max 20, min 2 characters; `GET /v1/clinical/drug-knowledge-base` returns attribution only | `DrugKnowledgeBaseApiTest` |
| 5 | Attribution text (source name, data month) | pending | `app.kb.source-name` and `app.kb.data-month` (YYYY-MM) config; footer "Drug data: <KB> <Month YYYY>" on the Rx pad; not shown until both are set | `DrugKnowledgeBaseApiTest`, `cln018-drug-kb-attribution.test.mjs` |
| 6 | Feed format and cadence (monthly full, weekly delta) | pending | import is CLN-019; sample validated with `scripts/kb/feed_validate.py` | `scripts/kb/test_feed_validate.py` |
| 7 | GCC terms | out of scope | negotiated in Phase 3 | n/a |

## When a feed is loaded
Set `app.kb.source-name` (exactly as the attribution clause words it) and `app.kb.data-month` for the month of the loaded
data, per cell, at each monthly load. Any KB-derived warning or drug detail must show the same source and month
(CLN-019/AI-01 will use the same endpoint); warnings from the CSO dictionary keep their own "Source: ..." line.

## Sample feed validation
`python3 scripts/kb/feed_validate.py --feed <sample.csv> --top500 <top500.csv> --report report.md` passes when at least
95% of the 500 brands resolve to a generic and a strength. The column names are provisional until the feed spec is agreed.
