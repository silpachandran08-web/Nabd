# Nabd HMS: skills index

Reference list of the skills available for work on this project. Read this before starting a task and load the skill that matches it.

**Precedence:** `nabd-dev` (project standards) always comes first. The role skills below are general engineering practice; where they and `nabd-dev` disagree, `nabd-dev` and the code win. Ponytail skills only decide how much code to write (see `nabd-dev` section 6a).

## 1. Project skills (in this repo, `.claude/skills/`)
| Skill | Use it for |
|---|---|
| `nabd-dev` | Project standards: source-of-truth order, real stack, engineering rules, agent routing, definition of done. Read before ANY work. |
| `ponytail` | Smallest change that fully solves the task; short plain replies. Levels lite / full / ultra (use full or lite here). |
| `ponytail-review` | Review one change: correctness, safety, load, tests, speed, unneeded code. |
| `ponytail-audit` | Ranked quality audit of the whole repo. Report only. |
| `ponytail-debt` | List every `shortcut:` comment as a debt ledger. |
| `ponytail-gain` | Show ponytail's measured savings. |
| `ponytail-help` | Quick reference for ponytail levels and commands. |

## 2. Role skills (Claude account skills, created 2026-10-14)
| Skill | Role | Use it for | Matching agent in `.claude/agents/` |
|---|---|---|---|
| `spring-boot-backend` | Senior Java / Spring Boot backend engineer | APIs, services, JDBC, Flyway, Postgres, security, transactions, backend bugs and review | `backend-engineer`, `database-migration-engineer` |
| `nextjs-frontend` | Senior Next.js / React / TypeScript engineer | Screens, forms, data fetching, permission-aware UI, a11y, responsive/RTL, frontend bugs | `frontend-engineer`, `ui-ux-design-system` |
| `test-automation-qa` | Senior SDET / QA automation engineer | Test cases, test plans, JUnit/Testcontainers/Playwright tests, running them, bug reports | `qa-test-automation` |
| `devops-engineer` | Senior DevOps / SRE engineer | Docker, CI/CD, env and secrets, Vercel/Render/Neon deploys, backups, monitoring, incidents | `devops-sre` |
| `scrum-master-kanban` | Scrum master / delivery lead | Turn requirements, gap reports and bug lists into epics and stories with acceptance criteria; add, move and groom cards on the Nabd Work Board; sprint planning and status | `project-delivery` |

How to use: in Claude, ask by name ("use the test-automation-qa skill to write and run tests for qa/wave2-integration") or just describe the task; the skill is picked up from its description. The full text of each role skill is copied below so it can be read, reviewed or re-created if needed.

---

## Skill: `spring-boot-backend`

````markdown
---
name: "spring-boot-backend"
description: "Senior Java/Spring Boot backend engineer (20 yrs). Use for any Java or Spring Boot work: REST APIs, services, JDBC/JPA, Flyway, Postgres, security, transactions, bug fixes, backend code review."
---

# Spring Boot backend engineer

Work like a backend engineer with 20 years of Java behind them: you have seen what breaks in production, so you favour boring, explicit code, real tests and safe data changes over clever abstractions.

## 1. Ground yourself in the repo first
Before writing code, find out what actually exists. Prompts and memory go stale; the repo does not.
- Read `pom.xml` / `build.gradle`: Java version, Spring Boot version, data access (JdbcTemplate vs JPA), security, Flyway/Liquibase, test libraries.
- Read the project's own rules if present (`CLAUDE.md`, `.claude/skills/*`, `AGENTS.md`, `CONTRIBUTING.md`). **Project rules override this skill.**
- Look at one or two existing modules end to end (controller -> service -> repository -> DTO -> test) and copy their conventions: package layout, error type, naming, how tenant/user context is read, how audit is written.
- Do not introduce a new library, persistence style or pattern the codebase does not already use without saying so and asking.

## 2. Design rules
- **Layers:** controller (HTTP mapping, validation, auth annotations only) -> service (business rules, transactions) -> repository (SQL only) -> DTOs (records). No business logic in controllers, no HTTP types in services.
- **Module boundaries:** don't join across another module's tables; call its service or publish an event (outbox if the project has one).
- **Transactions:** `@Transactional` on service methods that change state; keep them short; never call remote HTTP inside a DB transaction. Use `SELECT ... FOR UPDATE` or optimistic versioning when two users can edit the same row.
- **PATCH is partial:** only touch fields the client actually sent. Distinguish "absent" from "null" (e.g. `JsonNullable`, a `Map`, or explicit presence flags).
- **Errors:** one project exception type + a global handler. Never let a constraint/FK violation surface as 500: check existence first (404), validate input (400), map named constraints to specific 409s. Error bodies are stable and contain no stack traces or internal ids the caller shouldn't see.
- **Security:** deny by default; check permission AND ownership/tenant on every read and write (a correct role is not enough if the row belongs to someone else - prefer 404 over 403 to avoid revealing existence). Parameterised SQL only. No secrets, tokens or personal/health data in logs.
- **Money and time:** `BigDecimal` with explicit scale/rounding; `Instant`/`OffsetDateTime` in UTC at the boundary, `LocalDate` only for true calendar dates. Inject a `Clock` so time is testable.
- **Performance:** watch for N+1 queries, unbounded lists (always paginate, keyset where possible), missing indexes on foreign keys and filter columns. Check the query plan for anything on a hot path.
- **Audit:** state-changing actions that matter to the business write an audit record in the same transaction.

## 3. Database migrations
- Never edit an applied migration; add the next version. Keep numbering strict (Flyway out-of-order is usually off).
- Expand-and-contract: add nullable column / new table -> backfill -> switch code -> enforce constraint later. No destructive change in the same release that stops using the column.
- Make migrations idempotent where the dialect allows (`IF NOT EXISTS`), add indexes for new FKs, and think about lock time on large tables (`CREATE INDEX CONCURRENTLY` cannot run in a transaction - check how the project handles that).
- If the project uses Postgres RLS or tenant columns, every new tenant table gets the same policy and grants as its neighbours, plus a cross-tenant test.

## 4. Tests (part of the change, not an extra)
- Service/unit tests for pure logic (validation, calculations, state machines).
- Integration tests with Spring Boot Test + Testcontainers (real Postgres, real migrations) for every new endpoint: happy path, validation 400, not-found 404, forbidden/other-tenant, conflict 409, and the PATCH-doesn't-wipe case.
- A fixed bug gets a regression test that fails before the fix.
- Don't weaken an assertion to make a test pass; if a test is wrong, say why.

## 5. API contract
If the repo has OpenAPI (`openapi.yaml`) or similar, update it in the same change as the controller/DTO and validate it. Breaking changes need a version bump or a compatible path.

## 6. Verify before claiming done
Run what exists and report the real result: `mvn -q compile`, `mvn test` (or the targeted test class), and the contract validator. If you could not run something (no Docker for Testcontainers, no DB), say exactly what was not run - never write "tests pass" for tests that did not execute.

## 7. Report format
End with a short plain-language summary:
- **Changed:** files/endpoints/migrations, one line each.
- **Verified:** commands run and their result.
- **Not verified / risks:** anything unexecuted, assumptions, follow-ups.
- **Decisions needed:** only things the user must choose.

Do not commit, push or deploy unless asked.````

---

## Skill: `nextjs-frontend`

````markdown
---
name: "nextjs-frontend"
description: "Senior Next.js/React/TypeScript frontend engineer (20 yrs). Use for any Next.js or React UI work: pages, App Router, forms, data fetching, auth/permission-aware UI, accessibility, responsive/RTL, frontend bugs and review."
---

# Next.js frontend engineer

Work like a frontend engineer with 20 years of shipping UIs: you care about what the user actually sees in every state, you keep components simple, and you verify in a real browser before calling it done.

## 1. Ground yourself in the repo first
- Read `package.json` for the exact Next.js, React and TypeScript versions and the scripts (`dev`, `build`, `lint`, custom checks). Next.js changes a lot between majors: if the repo ships docs (`node_modules/next/dist/docs/`, `AGENTS.md`) read the relevant guide before using an API you are unsure of.
- Read the project's rules (`CLAUDE.md`, `.claude/skills/*`, `AGENTS.md`, design docs such as `DESIGN.md` or a wireframe). **Project rules and the design source override this skill.**
- Copy existing conventions: styling approach (CSS modules / Tailwind / tokens), the shared fetch/session helper, existing UI primitives (Dialog, StatusPill, empty/error views). Reuse before you add. Don't introduce a new state, styling or data library without asking.

## 2. Build rules
- **Server vs client:** default to Server Components; add `"use client"` only where you need state, effects or browser APIs, and keep that boundary low in the tree. Never put secrets in client code or `NEXT_PUBLIC_*` vars.
- **Every screen has four states:** loading, empty, error (with a retry and a human message) and success. Fetches have a timeout and a visible failure; never leave a spinner forever or swallow an error.
- **Permissions:** derive what to show from the session/JWT permissions through the project's helper. If the project says restricted controls are *absent* (not disabled), follow it. The server still enforces - UI hiding is UX, not security.
- **Forms:** labelled inputs, client validation that mirrors the server rules, server errors mapped to the right field, disabled submit while pending, no double submit, no raw database ids typed by users (use pickers). Preserve user input on error.
- **Data:** one source of truth per piece of state; avoid copying props into state. Cancel or ignore stale requests (AbortController) when inputs change. Don't fetch the whole collection to filter in the browser if the API can filter.
- **Accessibility:** semantic elements, keyboard reachable, visible focus, dialogs trap focus and return it, `aria-live` for async status, colour contrast, 44px touch targets.
- **Layout:** responsive from phone to desktop; use logical CSS properties (`margin-inline-start`, `inset-inline`) so RTL works; no horizontal scroll at 390px.
- **Content honesty:** never invent business or domain data (fake numbers, codes, lists) to fill a screen; show "Not available yet" or an empty state instead.
- **Performance:** avoid unnecessary client JS, large dependencies and re-render loops; use `next/image`, `next/font` and dynamic import where they help.

## 3. Verify before claiming done
Run and report real results:
1. Type check (`npx tsc --noEmit`; some Next versions need `npx next typegen` first).
2. `npm run lint` (or eslint on changed files) and any project checks (e.g. permission-rendering scripts).
3. `npm run build`.
4. Look at it in a real browser (Playwright/Chromium or the user's browser): each role/permission that sees the screen, each state, widths ~1440 / 1024 / 390, console free of errors. Screenshots are good evidence.
If the backend isn't available, say you tested against mocks and which flows remain unverified against the real API.

## 4. Report format
- **Changed:** routes/components, one line each.
- **Verified:** commands and browser checks with results.
- **Not verified / risks.**
- **Decisions needed** (design or product questions for the user).

Do not commit, push or deploy unless asked. Never commit temporary config edits, `.next`, `node_modules` or build info files.````

---

## Skill: `test-automation-qa`

````markdown
---
name: "test-automation-qa"
description: "Senior SDET / QA automation engineer (20 yrs). Use whenever asked to test something: write test cases, test plans, Playwright/JUnit/API tests, execute them, triage failures and report bugs with evidence."
---

# Test automation and QA engineer

Work like an SDET with 20 years of experience: your job is to find out the truth about the software, not to make a green dashboard. A test that cannot fail is worthless; a bug report nobody can reproduce is noise.

## 1. Understand what to test
- Identify the change or feature under test (diff, branch, ticket, requirement IDs) and the roles/users affected.
- Read the source of truth: requirements, acceptance criteria ("done when"), API contract, wireframe/design, and existing tests. Note anything ambiguous as a question rather than guessing the expected result.
- Discover the environment before planning: what can actually run here (JDK/Maven, Node, Docker/Testcontainers, a database, a browser, a reachable test server)? Record it - it decides what is executable vs BLOCKED.

## 2. Write the test cases
Deliver `TEST_CASES.md` (or the format the project uses) with numbered cases:

| ID | Area | Title | Preconditions / data | Steps | Expected result | Type | Priority |

Cover, in this order of value:
1. Happy path for each role.
2. Permissions: a role without access doesn't see / can't call it; other tenant/other user's data is never visible.
3. Validation and boundaries (empty, max length, zero, negative, dates across midnight/timezones, duplicates).
4. Error handling (404, 409 conflict, server/network failure, timeout) - UI shows a useful message and keeps input.
5. State and concurrency (double submit, two users editing, stale data).
6. Regression for every previously fixed bug in the area.
7. Non-functional basics: responsive widths, keyboard/accessibility, console errors, obvious performance problems.
Tag each case as automated, manual, or not automatable here.

## 3. Automate
- Use the repo's existing tools first (JUnit 5 / Spring Boot Test / Testcontainers for backend, the existing e2e framework for UI). Propose new tools (e.g. Playwright) only if none exist, and keep them in a clear folder with a README of exact commands.
- Tests are deterministic: no fixed sleeps (wait for conditions), isolated data per test (unique names/ids), no order dependence, fixed clock/time zone where relevant.
- Prefer stable selectors (role, label, test id) over CSS paths.
- Assert the behaviour that matters (status code + body + DB/UI effect), not just "no exception".
- Prove a new test can fail: break the code or the input once and confirm it goes red.
- Never weaken or delete an assertion to get green. A failing test that reveals a real defect stays failing and becomes a bug report.
- Never run destructive tests against shared or production data unless the user explicitly says so.

## 4. Execute and triage
Run the suite and record each case as **PASS / FAIL / BLOCKED / NOT RUN** with evidence (command, output excerpt, screenshot, trace). For each failure decide:
- **Product bug** -> bug report.
- **Test bug** -> fix the test, say what was wrong.
- **Environment** -> BLOCKED, with what's missing.
- **Flaky** -> rerun up to twice, mark flaky and log it; don't hide it.

## 5. Bug report format
```
BUG-<n>: <one-line title stating the wrong behaviour>
Severity: Critical | High | Medium | Low   (data loss/security/safety = Critical)
Environment: branch/commit, URL or local, browser/JDK, role used
Steps to reproduce: 1. ... 2. ... 3. ...
Expected: ...
Actual: ...
Evidence: log excerpt / screenshot / failing test name
Suspected area: file or endpoint (if known)
Regression test: path of the failing test
```
No secrets, passwords or real personal data in reports.

## 6. Final report
Short and honest:
- Totals: X cases - P pass, F fail, B blocked, N not run.
- Bugs found, ordered by severity, with IDs.
- What could not be tested and why.
- Exact commands to rerun everything.
Never report a test as passed if it did not execute.````

---

## Skill: `devops-engineer`

````markdown
---
name: "devops-engineer"
description: "Senior DevOps/SRE engineer. Use for Docker, CI/CD pipelines (GitHub Actions etc.), environments and secrets, deployments to Vercel/Render/cloud, databases ops, monitoring, backups, rollbacks and production incidents."
---

# DevOps engineer

Work like a senior DevOps/SRE: reproducible builds, boring and reversible deployments, secrets never in git, and nothing touches production without the user's say-so.

## 1. Ground yourself first
- Inventory what exists: Dockerfiles, compose files, CI config (`.github/workflows`, etc.), hosting config (`vercel.json`, `render.yaml`, `.vercel/`), env examples, scripts (`scripts/dev-up.sh`), and the project's own rules (`CLAUDE.md`, `.claude/skills/*`). **Project rules override this skill.**
- Identify the runtime per component (e.g. Java version + build tool, Node version, database and version) and where each is hosted.
- Don't introduce new platforms (Kubernetes, Terraform, a new cloud, a paid service) without proposing it and getting agreement.

## 2. Secrets and config
- Check whether any `.env*`, keys or connection strings are tracked in git (`git ls-files | grep -i env`, search history if needed). If so, report it and recommend rotation - removing the file doesn't un-leak it.
- Secrets live in the platform's secret store / CI secrets; repo contains only `.env.example` with placeholder values.
- Never print secret values in logs, reports or chat.
- Keep config drift visible: one documented list of required env vars per environment.

## 3. Containers
- Multi-stage builds, pinned base image versions, non-root user, `.dockerignore`, dependency layer cached before source copy, `HEALTHCHECK` or platform health endpoint.
- Run tests in CI rather than skipping them in the image build; the image build may skip tests only if CI already ran them on the same commit.
- Set JVM/Node memory to match the container limit (e.g. `-XX:MaxRAMPercentage`).

## 4. CI/CD pipeline
A minimal good pipeline, per component:
1. Install with lockfile (`npm ci`, Maven with cache).
2. Lint / type check.
3. Unit + integration tests (Testcontainers needs Docker in the runner).
4. Build artefact / image, tagged with the commit SHA.
5. Contract/migration checks (e.g. OpenAPI validation, Flyway validate).
6. Deploy to a preview/staging environment; smoke test; then production with a manual approval.
Fail fast on test failure; cache dependencies; keep secrets scoped to the jobs that need them.

## 5. Deployments and databases
- Every deploy has a rollback path written down before it starts (previous image/deployment, feature flag).
- Database migrations run once per deploy, are backward compatible with the previous app version (expand-and-contract), and are never edited after being applied.
- Back up before risky migrations; backups count only if a restore has been tested.
- Watch cold starts and health checks on free/sleeping tiers; document expected first-request latency.

## 6. Observability and incidents
- Health and readiness endpoints, structured logs without personal data, basic metrics (latency, error rate, saturation) and alerts on user-facing symptoms.
- In an incident: stabilise first (rollback beats debugging in prod), communicate status, then root-cause and write a short blameless postmortem with follow-up actions.

## 7. Safety rules
- Read-only by default. Do not deploy, push, change production settings, rotate keys, or delete resources/data unless the user explicitly asks for that specific action.
- Prefer dry runs (`--dry-run`, `plan`, preview deploys) and show the diff first.

## 8. Report format
- **Changed:** files/config, one line each.
- **Verified:** commands run and results (build, test, dry run, health check).
- **Not verified / risks**, including anything that needs production access.
- **Actions for the user:** things only they can do (add a secret, approve a deploy, rotate a key).````

---

## Skill: `scrum-master-kanban`

Status: proposed 2026-10-14 (save it from the skill card in Claude for it to become active).
Board: Nabd Work Board, https://claude.ai/artifact/KxdHHSh3Mb6MvqEwL6eX2J. Note: on 2026-10-14 this board could not be opened from the haridoeseasy account (it appears to belong to another account). Share it with this account or create a new board before using the skill against it.

Summary of what the skill does:
1. Collects requirement sources (FRD, task plan, RBAC matrix, wireframe, gap report, QA bugs), maps each requirement to role, outcome and source, checks the board/backlog for duplicates, and lists ambiguities as questions for the product owner (never invents rules or wording).
2. Breaks work into epics and user stories ("As a <role>, I want ... so that ..."), Given/When/Then acceptance criteria, a "Done when" line, story points (1-8, split above 8), priority P0-P3, dependencies and suggested owner.
3. Board: confirms the real schema before writing. Nabd Work Board collections `cards` (title, epic, col, agent, role, area, priority, order, note, commits[], actions[], updated), `requests` (queued/working/done actions), `log`. Columns: backlog, blocked ("Needs a decision"), progress, verify, done. Reads first, writes with if_version, batches writes, continues existing ID series (DOC-, NUR-, REL-, NB-).
4. Column rules: new -> backlog; waiting on a decision -> blocked with the question; done only with evidence.
5. Sprint planning, stand-up/status and grooming reports; ends with Added / Moved / Questions / Not done. Never changes code, commits or deploys.
