---
name: nabd-dev
description: Project standards for Nabd HMS (clinic/hospital SaaS). Use for ANY development, review, bug fix, migration, API or UI work in this repo. Defines source-of-truth order, real tech stack, engineering rules, agent routing, workflow and definition of done.
---

# Nabd HMS: development skill (always read first)

This file is the standing reference for all future development in this repo. `CLAUDE.md` imports it, and every agent in `.claude/agents/` loads it. When this file and an agent prompt disagree, this file and the code win. If you change a rule here, say so in the same change.

## 1. What Nabd is
Multi-tenant clinic/hospital SaaS, from a two-doctor dental clinic to a multi-brand hospital group. Region (IN or KSA) is chosen at provisioning and is irreversible. Two surfaces: the **clinic app** (Owner, Receptionist, Doctor, Nurse, custom roles) and the **platform console** (Nabd's own operators: super admin, implementation, support engineer, billing, SRE, commercial read-only, compliance/DPO). Patient and caregiver surfaces are WhatsApp-based and mostly unbuilt.

Live: frontend https://nabd-nu.vercel.app (Vercel), backend on Render (`/v1`), database on Neon. Branch in use: `develop-initial`.

## 2. Source of truth (highest first)
1. **Safety and compliance rules** (section 6) override everything.
2. **Wireframe**: `Nabd Shell.dc.html` + `DESIGN.md` + `Nabd Design System.dc.html` (local folder `../startfromremaining-wireframe/project/`; module snapshots named `... vNN - before ...` are older and only supplementary).
3. **Requirements and permissions**: `Nabd HMS - Implementation Task Plan v2.xlsx` (sheets Backlog, Requirement Coverage, RBAC Matrix, SaaS Operator Roles, Security Controls, Risks). Task IDs are `NB-nnn`; requirement IDs like `PAT-01`, `AGG-01`.
4. **Architecture**: `Clinic_Hospital_SaaS_Architecture_v1.0.html` (local parent folder).
5. **API contract**: `api/openapi.yaml`.
6. **Code**. If code and wireframe differ, the wireframe is the target unless the user says otherwise.
7. **Known defects and gaps**: `../ui-gap-report/00-SUMMARY.md` (303 UI findings by role, IDs REC-, DOC-, NUR-, OWN-, PLT-, PHA-, PAT-F, CGV-, AGG-F) and `../test/qa-care-hospital-test-results.md` (QA bugs #1-#8).
8. **Backlog of the 303 UI findings**: `docs/backlog/ui-gap-backlog.csv` (in the repo; priority, wave, owning agent, status). Update the row when you fix a finding.
Items 2-4 and 7 sit outside the git repo (parent folder `Nabd/`). Until they are copied into `docs/`, say so when a teammate cannot see them.

## 3. Real stack (the repo wins over any prompt or manifest)
| Area | In the repo | Not in the repo (needs an ADR before use) |
|---|---|---|
| Backend | Java **17**, Spring Boot **3.3.4**, Spring Security + OAuth2 resource server, **Spring JDBC** (JdbcTemplate), Flyway, BouncyCastle, Spring Mail | Java 21, JPA/Hibernate |
| DB | PostgreSQL, RLS on tenant tables, `master` schema for operators, migrations V1-V48 in `db/migrations` (next **V49**) | PgBouncer config, partitioning, ClickHouse/TimescaleDB |
| Frontend | Next.js **16.3.1**, React **19.2**, TypeScript, CSS modules, `--nb-*` tokens, `lib/session.ts` | Tailwind, Shadcn, TanStack Query, Zustand, Storybook |
| Messaging | Transactional outbox (`OutboxDispatcher`), WhatsApp handlers | Kafka, RabbitMQ, Redis |
| Ops | Docker (Maven+Temurin 17), Vercel, Render, Neon, `scripts/dev-up.sh` | Kubernetes, Terraform, Datadog |
| Tests | JUnit 5, Spring Boot Test, Testcontainers | Playwright, SonarQube, Allure (recommended additions) |
Frontend note: `frontend/AGENTS.md` says this Next.js version has breaking changes. Read the matching guide in `node_modules/next/dist/docs/` before writing frontend code.

## 4. Engineering rules
**Backend**
- Follow the module layout `controller / service / repository / dto` under `com.nabd.hms.<module>`. No cross-module SQL joins; call the other module's service or publish an outbox event.
- Errors go through `ApiException` and `GlobalExceptionHandler`. Never let a foreign-key or constraint violation surface as 500. Check existence first (404/400); map constraint names to specific 409s.
- PATCH means partial update. Never overwrite columns the request did not send (see the guardian-revoke bug).
- Every state-changing action writes an audit event (`AuditService`). Sensitive actions need step-up. Never log PHI.
- Money uses exact decimals. Stock changes are immutable log rows.
**Database**
- New migration = new file `V<n>__name.sql`; never edit an applied one. Expand-and-contract, idempotent, with RLS enabled and forced on every tenant table, plus a cross-tenant test.
**Frontend**
- Restricted elements are **absent**, not disabled (wireframe rule). Derive visibility from JWT permissions via `lib/session.ts`; keep it consistent with the RBAC Matrix. Run `npm run check:permissions`.
- No raw IDs in forms (use pickers). Every screen has loading, empty and error states. Fetches have timeouts and a visible failure.
- Dark-first tokens from `DESIGN.md`; logical CSS properties so RTL works; 44 px touch targets; responsive down to tablet and phone.
**API**
- Change `api/openapi.yaml` in the same change as the endpoint or DTO; validate with `python3 -m openapi_spec_validator api/openapi.yaml`.
**Security and secrets**
- No PINs, tokens, DB URLs or keys in code, docs or reports. Test credentials stay in the local `test/` folder.
- Anti-enumeration: unknown user and wrong PIN return the same generic 401.

## 5. Agents and routing
Agents live in `.claude/agents/`. Use the first match; involve more than one when a change crosses areas.
| Need | Agent |
|---|---|
| Module boundaries, tenancy/region, new technology, ADR | `solution-architect` |
| Spring services, controllers, bug fixes | `backend-engineer` |
| Schema, Flyway, RLS, indexes, query speed | `database-migration-engineer` |
| Screens, forms, permission rendering, API error UX | `frontend-engineer` |
| Tokens, components, tables, charts, RTL, responsive, a11y | `ui-ux-design-system` |
| `openapi.yaml` and contract drift | `api-contract` |
| Encounters, charting, prescriptions, triage, specialty templates | `clinical-workflow` |
| Invoices, checkout, packages, pharmacy stock | `billing-pharmacy` |
| WhatsApp/SMS/email, outbox, webhooks, consent for messaging | `messaging-integration` |
| Dashboards, reports, day close | `reporting-analytics` |
| Tests, regressions, QA tenant retests | `qa-test-automation` |
| Auth, MFA, PHI, secrets, tenant leakage (review) | `security-privacy` |
| PDPL, NPHIES, ABDM, DPDP, consent, residency (review) | `healthcare-compliance` |
| Dosing, allergy, interaction, triage escalation, risk register (review, can block) | `clinical-safety` |
| CI/CD, Docker, hosting, backups, observability | `devops-sre` |
| Backlog, sprints, scope creep, risks | `project-delivery` |
| README, runbooks, changelog, secret scan, release | `docs-release` |
Mandatory reviews: anything touching prescribing/dosing/allergy/triage goes past `clinical-safety`; anything touching auth, PHI, tenancy or secrets goes past `security-privacy`; any new consent, region or data-sharing behaviour goes past `healthcare-compliance`.

## 6. Non-negotiables
1. A clinical safety defect outranks any feature.
2. No cross-tenant or cross-region data access, ever. Test it.
3. PHI is never logged, never sent to external providers unmasked, never in URLs.
4. Consent is checked before access or messaging; revocation must not destroy clinical data.
5. Nothing is deployed, committed or pushed unless the user asks.

## 6a. Precedence over other coding skills (ponytail)
If the ponytail skills are installed, they decide only **how much code to write**. Use level `full` or `lite`, never `ultra` (it questions the request, and requests here are already decided by the wireframe and backlog). They never override:
1. The non-negotiables in section 6 and the mandatory reviews in section 5.
2. Scope: a backlog row and its 'Done when' line, and the wireframe, count as asked for. "Skip what nobody asked for" applies only to extras beyond them.
3. The definition of done in section 8: permission-based hiding, loading/empty/error states, tests, audit events, `openapi.yaml` and migrations are never "extras".
4. Validation at trust boundaries, error handling that prevents data loss, security, accessibility and PHI handling, which ponytail itself says never to cut.
Reuse-before-add still applies: use an existing helper, component or endpoint when one fits. Record any `shortcut:` comment you add in the Notes column of the backlog row. If ponytail and this file disagree, this file wins.

## 7. Workflow for every task
1. Read this file. Identify the role(s) and screen/module affected.
2. Look up the wireframe screen, the RBAC row, the backlog `NB-` IDs and any open finding IDs in the gap report or QA results.
3. Pick the agent(s) from section 5; keep the diff small.
4. Implement, then write or update tests; update `openapi.yaml`, add a migration if the schema changed, update docs.
5. Verify: backend `mvn test` (in `backend/`), frontend `npm run lint` and `npm run build` (in `frontend/`), `npm run check:permissions`, openapi validation; for UI changes look at the result in a browser.
6. Report: what changed, evidence it works, what is unverified, which finding/backlog IDs it closes.

## 8. Definition of done
- Matches the wireframe for the affected role(s), including permission-based hiding and empty/loading/error states.
- Backend: tests pass, no new 500 paths, audit event written, contract updated.
- DB: migration is additive and RLS-safe; cross-tenant test passes.
- No secrets or PHI in code, logs, docs or test data.
- Related finding IDs are named in the change description and marked Done in `docs/backlog/ui-gap-backlog.csv`.

## 9. Highest-priority open work (as of 2026-10-11)
Blockers: guardian revoke wipes address/national ID; WhatsApp sends never fire; reception billing worklist and appointment cancel/move UI; discount permission; doctor nav and My Day; owner Compliance & Audit screen; platform Command Centre, tenant record and missing console surfaces. Then QA bugs #1-#8, then the cross-cutting items: RTL/i18n, responsive layout, role-specific navigation, permission gating. Details: `../ui-gap-report/00-SUMMARY.md`.
