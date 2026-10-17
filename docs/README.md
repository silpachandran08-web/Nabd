# Nabd — planning and review documents

Two workstreams, written on 2026-10-17 against `main` at `03942d3`. "Nabd" is the working name throughout; see strategy/02 before using it publicly.

**Start with these three:**

| | |
|---|---|
| [00-SUMMARY.md](00-SUMMARY.md) | The whole plan on two pages, in plain language |
| [Nabd-Development-Tracker.xlsx](Nabd-Development-Tracker.xlsx) | Every code task to complete development (295 rows) with priority, phase, sprint, team, effort, acceptance criteria and status; live dashboard |
| [../prototype/](../prototype/README.md) | Clickable prototype of the end product: 13 roles, 70 screens, 4 markets, English/Hindi/Arabic |

## Validation snapshot (at `03942d3`)

| Check | Result |
|---|---|
| Backend `mvn test` | 560 tests, 0 failures, 0 errors (1 min 31 s). **Only after overriding the Docker API version** — Testcontainers 1.19.8 is too old for Docker Engine 29 and every integration test errors without the override (QA-34). |
| Frontend `npm ci` / `lint` / `test:unit` / `check:permissions` / `build` | All pass (9/9 unit tests). `npm audit`: 10 vulnerabilities, 1 critical (next@16.3.1 → upgrade to ≥ 16.4.2). |
| Frontend `tsc --noEmit` | Fails on a fresh checkout (`LayoutProps` missing); passes after `next build` / `next typegen`. CI must run typegen first. |

A green test suite does not mean a safe system: the reviews below found critical authentication and tenancy defects the tests do not cover. **No real patient data should enter the system until the "Phase 0" items in engineering/06 are closed.**

## Workstream 1 — Company & product (`strategy/`)

| Doc | What it answers |
|---|---|
| [01 Market, competitors & regulation](strategy/01-market-competitors-regulation.md) | Market dynamics in India, UAE, KSA, Qatar (+ Oman, Kuwait, Bahrain, Egypt); competitor table; DPDP/ABDM, UAE, KSA, Qatar rules; compliance matrix; launch market and pricing hypothesis |
| [02 Brand & naming](strategy/02-brand-and-naming.md) | Conflict screen on "Nabd" (verdict: replace), candidate names, top 3, trademark clearance checklist |
| [03 Personas, interviews & requirements](strategy/03-personas-interviews-requirements.md) | 16 personas, simulated interviews, journeys, 120-item MoSCoW backlog, UX principles and time targets |
| [04 Current product inventory](strategy/04-current-product-inventory.md) | What is actually built (with file evidence), maturity per module, where the plan spreadsheet and specs drift from the code |
| [05 Gap analysis & product roadmap](strategy/05-gap-analysis-and-product-roadmap.md) | Gap matrix for every Must/Should feature, phases 0–5, 42 epics in build order, team plan, founder decisions |

## Workstream 2 — Engineering review (`engineering/`)

| Doc | What it answers | Finding IDs |
|---|---|---|
| [01 Backend architecture](engineering/01-backend-architecture-review.md) | Module structure, tenancy, transactions, scalability, microservices recommendation | `F-xx` (cited as ARCH-xx in 06) |
| [02 Security, tenancy & privacy](engineering/02-security-tenancy-privacy-review.md) | Isolation, authN/authZ, audit, data protection, compliance mapping | `F-xx` (cited as SEC-xx in 06) |
| [03 Data, performance & caching](engineering/03-data-performance-caching-review.md) | Schema and indexes with measured timings, query patterns, caching and real-time refresh design | `DP-xx` |
| [04 Frontend](engineering/04-frontend-review.md) | Structure, data layer, auth handling, UX for speed, accessibility, i18n/RTL, mobile strategy | `FE-xx` |
| [05 QA strategy & findings](engineering/05-qa-test-strategy-and-findings.md) | Test inventory and coverage, confirmed defects, test strategy, CI gates, run results | `QA-xx` |
| [06 Target architecture & remediation plan](engineering/06-target-architecture-and-remediation-plan.md) | Consolidated findings register, ADRs, target architecture, sprint plan | master IDs |
| [07 Consent & data-sharing architecture](engineering/07-consent-and-data-sharing-architecture.md) | Global patient/doctor identity, consent record, Exchange service, intra-hospital access settings | — |

Docs 01 and 02 were written independently and both use `F-xx`; `F-01` in one is not `F-01` in the other. Doc 06 prefixes them (ARCH-/SEC-) to remove the ambiguity. Strategy/05 cites backend-review IDs as `F-`.

## Caveats

- Personas and interviews (strategy/03) are synthesized hypotheses, not real research; the doc includes the plan for real interviews.
- Regulatory and trademark findings come from web research, often secondary sources; each doc marks unverified items. Legal counsel and a trademark attorney must confirm before acting.
- Code findings cite file and line at `03942d3`; line numbers will drift as the code changes.
