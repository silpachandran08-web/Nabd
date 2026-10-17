# 04 — Frontend Review: Nabd HMS Web App

**Scope:** `/frontend` (Next.js 16.3.1, React 19.2.8, TypeScript 5, CSS Modules). 135 TS/TSX files (25,029 LOC) plus 10,161 LOC of CSS, 40 routes, 0 runtime dependencies other than `next`, `react` and `react-dom`.
**Reviewer stance:** Principal Frontend Engineer with UX and accessibility focus. Read-only review. Every finding was checked against the code and cites `file:line` (paths are relative to `frontend/` unless noted).
**Date:** 2026-10-17. **Branch:** working tree at `03942d3`.

---

## 0. Verification run and method

| Check | Result | Notes |
|---|---|---|
| `npm ci` | OK | 10 advisories (9 high, 1 critical). With `--omit=dev`: **`next` 16.0.0–16.3.7 is critical** (GHSA-p293-qw3h-jr36, GHSA-2xp9-vwfh-vxw4, GHSA-mcj8-r9mp-w47p cache poisoning, and others). `sharp` and `source-map-js` are high. |
| `eslint` | Pass (no output) | `eslint-config-next` core-web-vitals and typescript presets. 17 `eslint-disable` lines: 10 `react-hooks/set-state-in-effect`, 7 `react-hooks/exhaustive-deps`. |
| `tsc --noEmit` | **Fails, 2 errors** | `app/layout.tsx:19` and `app/platform/layout.tsx:4`: `Cannot find name 'LayoutProps'`. This is a pipeline problem, not a code bug. `LayoutProps` is the global helper that `next typegen`/`next build` generates (see `node_modules/next/dist/docs/01-app/02-guides/upgrading/version-16.md:297-303`). CI must run `next typegen && tsc --noEmit`. |
| `npm run test:unit` | 9/9 pass | Only `tests/unit/vitals.test.mjs`. |
| `npm run check:permissions` | Pass | Static regex check only (see §4). |
| `next build` (Turbopack) | Pass | 38 static routes + 4 dynamic (`/accept-invite/[token]`, `/checkout/[queueEntryId]`, `/consult/rx/[id]/print`, `/documents/[id]/print`, `/owner/accept-invite/[token]`). `.next/static/chunks` total is 2.0 MB uncompressed. The largest chunk is 229 KB (framework). |

`AGENTS.md` warns that this Next.js version has breaking changes, so I read the bundled docs before judging API use. Two examples: `proxy.ts` replaces `middleware.ts` (`01-getting-started/16-proxy.md`), and `experimental.useOffline` with `useOffline()` from `next/offline` is now available (`02-guides/offline-support.md`). The app uses neither. Its one Next 16-specific API, `LayoutProps`, is used correctly. Async `params` are used correctly in `app/consult/rx/[id]/print/page.tsx` (`await params`).

---

## Executive summary

Nabd's frontend was built carefully, ticket by ticket, by people who clearly care about clinical safety. The code comments explain *why*, not just *what*. The consult note has a real two-tier autosave with conflict detection. Status is never shown by colour alone. Design tokens are used everywhere (3,415 `var(--nb-*)` references against 9 hex literals in module CSS). The CSS is already almost entirely written with logical properties, which makes RTL unusually cheap to add. The core `Dialog` primitive and the drug combobox meet a good accessibility bar.

The architecture has not kept pace with that care. The app is a **client-only SPA running inside Next.js**: 38 of 40 pages are `"use client"`, and all data is fetched in `useEffect`. There is **no shared API client**: there are 32 copies of `API_BASE` and about 20 copy-pasted `authedFetch` closures. The clinic app **never refreshes its 15-minute access token**, even though a 30-day refresh token is stored and `/auth/refresh` exists. Users are bounced to `/login` every time the token expires. Tokens live in `localStorage`. Live screens poll every 15 s with N+1 request fan-out (nursing sends roughly 2N+6 requests per tick). Seven page components are god components of 835–1,489 lines with 29–64 `useState` hooks each. There is **no i18n layer at all**: about 2,700+ hard-coded English strings, `lang="en"` with no `dir`, and a Latin-only font. JWT name claims are decoded with `atob`, which **corrupts every Arabic or Devanagari staff or clinic name**. Test coverage is one 64-line vitals test.

**Top 5 actions:**
1. Build one API client package with single-flight refresh, typed from a codegen'd OpenAPI spec, and adopt TanStack Query.
2. Move tokens to an httpOnly cookie through a same-origin BFF, and bump Next to ≥16.4.2.
3. Replace polling with SSE for queue, arrivals, nursing and My Day.
4. Add next-intl with `dir`, Arabic and Devanagari fonts, and a string-extraction pass.
5. Add Playwright E2E for the six core journeys and split the god components into feature modules.

---

## 1. Structure

### 1.1 App Router usage and server vs client components

- **Client density: 94 of 135 TS/TSX files start with `"use client"`, including 38 of 40 `page.tsx` files.** The only server pages are `app/page.tsx` (a `redirect("/login")`) and `app/consult/rx/[id]/print/page.tsx` (a 7-line wrapper that renders a client `RxPrint`).
- The root layout `app/layout.tsx:19-27` wraps everything in a client `AppShell` (`app/AppShell.tsx:1`) and a client `IdleLockGuard`. Every page's data is fetched from the browser after hydration. Pages render a skeleton, or `null`, until then. `AccessGate` deliberately returns a skeleton on the server snapshot (`app/components/ui/AccessGate.tsx:13-22`).
- This is a defensible *consequence* of storing the session in `localStorage`, because the server cannot know who the user is. It also means the app gets none of the App Router's benefits: no server fetching, no streaming, no `loading.tsx` that does anything useful, no route-level caching and no Server Actions (0 `"use server"`). It pays Next's complexity cost for SPA behaviour. The 14 `loading.tsx` files under `app/platform/**`, `app/owner/**` and `app/setup/departments/` only cover the JS chunk load. They never cover data.
- **No `error.tsx`, `global-error.tsx` or `not-found.tsx` anywhere.** An uncaught render error in `/consult` unmounts the tree to a blank page. The IndexedDB draft (§3.2) limits note loss to the last 800 ms. Everything else on screen is lost and the user gets no message.
- **Metadata:** one `metadata` export in total (`app/layout.tsx:14-17`, title "Nabd", description "Nabd clinic staff sign-in"). Every tab is titled "Nabd". Receptionists with four tabs open cannot tell them apart, and screen-reader users get no page-change announcement.
- `app/page.tsx` redirects unconditionally to `/login`, even for a signed-in user. `/login` then has to bounce them back to their landing page.

### 1.2 Route organisation

Routes are flat and role-oriented (`arrivals`, `consult`, `nursing`, `my-day`, `pharmacy`, `platform/*`, `owner/*`). That reads well. The problems are:
- **No route groups or segment layouts for shells.** The clinic shell is decided at runtime by `SHELL_SKIP_PREFIXES` (`app/lib/session.ts:126`), and `IdleLockGuard` keeps its own drifted copy (`app/IdleLockGuard.tsx:13`, which is missing `/accept-invite`). Route groups such as `(clinic)`, `(platform)`, `(owner)` and `(auth)`, each with its own `layout.tsx`, would make this structural instead of a string list.
- **Feature code is split between `app/<route>/` and `app/components/<feature>/`** with no consistent rule. `app/components/consult/` holds 44 files that only `/consult` uses. `app/schedule/` keeps its dialogs next to the page (`WaitlistPanel.tsx`, `NewAppointmentDialog.tsx`, `LiveQueueBoard.tsx`). `app/patients/RegistryDialogs.tsx` is colocated, while `app/components/arrivals/CheckInDialog.tsx` is shared from components.
- **Three distinct nav implementations** live in one 517-line file. `ClinicNav.tsx:21-91` holds `NAV_ITEMS`, `NURSING_ITEMS`, `OWNER_ITEMS` and `DOCTOR_ITEMS`, each with its own permission rules, plus `PlatformNav` under `app/platform/`.

### 1.3 Largest files (top 15 TS/TSX by LOC)

| # | File | LOC | `useState` | Assessment |
|---|---|---|---|---|
| 1 | `app/consult/page.tsx` | 1,489 | 57 | **God component.** Queue list, preview, note editor with autosave and conflict handling, Rx pad orchestration, signing, amendments, transfer, dental, close-out. 30 inline `style={{}}`. |
| 2 | `app/arrivals/page.tsx` | 1,313 | 64 | **God component.** Board, polling, walk-in registration and duplicate review, guardian search, vitals modal, priority, delays, move, checkout picker. |
| 3 | `app/nursing/page.tsx` | 1,192 | 46 | **God component.** Worklist, vitals capture, procedures, administration orders, activity. |
| 4 | `app/patients/page.tsx` | 1,140 | 51 | **God component.** Registry, drawer, allergies, conditions, timeline, guardian, dental. |
| 5 | `app/packages/page.tsx` | 1,067 | 46 | **God component**, 23 inline styles. |
| 6 | `app/setup/page.tsx` | 847 | 29 | Large tabbed settings page. Splittable by tab. |
| 7 | `app/staff/page.tsx` | 835 | 34 | Staff, roles, step-up auth, permission builder. |
| 8 | `app/reports/page.tsx` | 545 | — | Moderate. |
| 9 | `app/ClinicNav.tsx` | 517 | — | Four nav models plus four badge fetchers (`:181-260`). |
| 10 | `app/login/page.tsx` | 507 | 19 | Nine-view state machine (`:21-22`). It should be a reducer or routes. |
| 11 | `app/platform/provisioning/page.tsx` | 471 | — | OK. |
| 12 | `app/platform/billing/page.tsx` | 471 | — | OK. |
| 13 | `app/checkout/[queueEntryId]/page.tsx` | 469 | — | Mixed concerns (charge picker, preview maths, invoice, payments). |
| 14 | `app/components/setup/PharmacyPanel.tsx` | 467 | — | OK. |
| 15 | `app/schedule/page.tsx` | 453 | — | OK, already partly split. |

The five largest pages hold **264 `useState` calls between them**. A 64-state component re-renders the whole board on every 15 s poll and every 60 s clock tick (`app/arrivals/page.tsx:341,347`). Only `consult` has started extracting pure logic (`consultFlow.ts`, `rxPadLogic.ts`, `doseLogic.ts`, `draftStore.ts`). That is the right pattern and the others should follow it.

### 1.4 Duplication (copy-paste inventory)

| Pattern | Copies | Evidence |
|---|---|---|
| `const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1"` | **32** | e.g. `app/consult/page.tsx:76`, `app/arrivals/page.tsx:46`, `app/lib/session.ts:139`, `app/IdleLockGuard.tsx:12` |
| Per-page `authedFetch` `useCallback` (token read, header merge, 401 → remove token → `/login`) | **~20** clinic copies | `app/consult/page.tsx:201-219`, `app/arrivals/page.tsx:207-225`, `app/nursing/page.tsx:250`, `app/patients/page.tsx:208`, `app/staff/page.tsx:175`, `app/setup/page.tsx:114`, `app/pharmacy/page.tsx:26`, … |
| Other fetch helper styles | 5 more | `app/platform/platformApi.ts` (the good one), `app/components/doctorlists/api.ts:5-10`, `app/components/myday/useMyDay.ts:58-70`, `app/components/myschedule/useMySchedule.ts:48`, raw `fetch` in `app/ClinicNav.tsx:187,210,233,253` |
| JWT payload decode | 5 | `app/lib/session.ts:18`, `app/consult/page.tsx:100-109`, `app/staff/page.tsx:128`, `app/components/myday/useMyDay.ts:30-37`, `app/components/myschedule/useMySchedule.ts:28` |
| `type Problem = …` | **24** | across pages |
| `type QueueEntry` | 4 (drifted: optional vs required `createdAt`/`source`) | `app/components/consult/shared.ts:9`, `app/arrivals/page.tsx:19`, `app/nursing/page.tsx:20`, `app/schedule/LiveQueueBoard.tsx:9` |
| `VISIT_LABEL` map | 5 | `app/consult/page.tsx:80`, `app/arrivals/page.tsx:41`, `app/nursing/page.tsx:86`, `app/patients/page.tsx:58`, `app/schedule/page.tsx:33` |
| `initials()`, `age()` | 4 and 2 | `app/overview/page.tsx:57`, `app/patients/page.tsx:77,117`, `app/ClinicNav.tsx:116`, `app/components/consult/shared.ts:73,81` |
| `problemDetail` / `problemMessage` | 3 | `app/platform/platformApi.ts:108`, `app/billing/page.tsx:307`, `app/components/arrivals/CheckInDialog.tsx:20` |
| `useEscape` / ad-hoc Escape hooks for hand-rolled modals | 4 | `app/billing/page.tsx:299`, `app/patients/RegistryDialogs.tsx:17`, `app/schedule/page.tsx:433`, `app/schedule/NewAppointmentDialog.tsx:75` |
| Currency formatter | 4 different implementations | `app/lib/money.ts:3-11`, `app/billing/page.tsx:111`, `app/reports/page.tsx:214`, `app/overview/page.tsx:141` |
| `REGION_TZ` map | 2 | `app/components/consult/DocumentSheet.tsx:5`, `app/components/consult/FollowUpPanel.tsx:12` |
| `round2()` | 2 | `app/checkout/[queueEntryId]/page.tsx:52`, `app/checkout/otc/page.tsx:29` |
| `Promise.resolve().then(...)` in effects (a workaround for `react-hooks/set-state-in-effect`) | **60** | e.g. `app/arrivals/page.tsx:333,358,368`. This is a symptom of fetching in effects, which a query library removes. |

`app/platform/platformApi.ts:7-15` records that the operator console already had exactly this problem ("copy-pasted into billing, plans, provisioning…, only some copies cleared the session on 401") and fixed it. The clinic side needs the same consolidation.

### 1.5 Design system (`app/components/ui`) maturity

`ui/` is 280 LOC: `Dialog`, `AccessGate`, `Icon` (inline SVG set), `StateViews` (`Skeleton`, `EmptyState`, `ErrorState`), `StatusPill`. Adoption is good where these exist: `Dialog` is imported in 27 files and `StateViews` in 54. **Maturity is low, about level 1 of 4.** There is no `Button`, `Input`, `Select`, `Field` (label + hint + error), `Table`, `Tabs`, `Drawer`, `Toast`, `Combobox`, `DateField`, `MoneyField` or `PhoneField`. Every page module re-implements `.actionBtn`, `.input` and `.select`, which `app/globals.css:101-108` openly works around by adding global hover and pressed states "because most page CSS modules… define their own… with no hover or pressed state at all". Five hand-rolled dialogs bypass `Dialog` and lose its focus trap and restore (§6). `IdleLockGuard` is 100% inline styles (`app/IdleLockGuard.tsx:91-121`). There are 134 inline `style={{}}` uses overall.

### 1.6 CSS approach and theming tokens

- CSS Modules with one global token file (`app/globals.css:3-63`): surfaces, borders, accent, semantic status colours (ok, warn, danger, info, sync, urgent), text levels, radius, spacing scale, motion. **Token discipline is excellent**: 3,415 `var(--nb-…)` uses against 9 hex and 7 `rgba` literals in module CSS.
- **Gaps.** Dark theme only ("no light theme was approved yet", `app/globals.css:2`). There is no `prefers-color-scheme` or `prefers-contrast` handling. Three tokens are referenced but never defined: `--nb-radius-sm`, `--nb-space-10` and `--nb-topbar-h`. Most uses have fallbacks, but `app/components/consult/consultParts.module.css:89` uses `var(--nb-radius-sm)` with no fallback, so it silently falls back to 0. There are no typography tokens (font sizes are literals; 115 rules use 9–11 px) and no z-index or elevation tokens (the idle lock hard-codes `zIndex: 9999`).
- **Breakpoints are ad hoc.** Media queries use 430 (24×), 1024 (22×), 640, 720, 1100, 768 (2×), 767, 900 and 600. Commit `012ef02` introduced 1024/768 for consult, but 768 is barely used elsewhere and 767 and 768 co-exist. CSS variables cannot drive media queries, so a shared breakpoint list (PostCSS custom media or a documented constant set) is needed.
- `@media (pointer: coarse)` sets a 44 px minimum height on controls (`app/globals.css:134-140`). `prefers-reduced-motion` is respected (`:127-131`).

### 1.7 Naming

Naming is mostly clear and domain-true (`queueEntryId`, `checkout_pending`, `landingPathFor`). Inconsistencies include `VISIT_LABEL` vs `VISIT_LABELS`, `fmtDate`/`formatDate`/`formatDay`, `problemDetail`/`problemMessage`, CSS file casing (`RxPad.module.css` vs `rxPrint.module.css` vs `PermissionMatrix.module.css`), and folder names (`myday`, `myschedule`, `doctorlists` vs routes `my-day`, `my-schedule`). `README.md` is still the create-next-app boilerplate.

---

## 2. Data layer

### 2.1 How API calls are made

There is no fetch wrapper in `lib/`. Each page builds `authedFetch` inline (§1.4), always calling `fetch(API_BASE + path)` with `Authorization: Bearer <localStorage token>` and `Content-Type: application/json`. Responses are consumed as `await res.json()` (288 call sites) and assigned to hand-written types. Errors are RFC 7807 `Problem` bodies, parsed ad hoc in each file.

### 2.2 Auth token storage and refresh: the most serious finding

- **Storage.** The access and refresh tokens are in `localStorage` (`app/login/page.tsx:45-46`, `app/accept-invite/[token]/page.tsx:46`, `app/owner/workspaces/page.tsx:130`, `app/account/page.tsx:138`). The owner pending token is in `sessionStorage` (`app/lib/session.ts:56-61`). The operator console uses separate keys (`app/platform/platformApi.ts:17-18`).
- **The clinic app never refreshes.** `grep "/auth/refresh"` finds it only in `platformApi.ts:30`, which is the platform endpoint. The backend issues **15-minute access tokens** (`backend/src/main/resources/application.yml:59`, used at `AuthService.java:576`) and 30-day rotating refresh tokens (`api/openapi.yaml:153-174`, "old refresh token invalidated"). Every clinic `authedFetch` treats 401 as "remove access token, `router.replace('/login')`" (e.g. `app/consult/page.tsx:212-216`, `app/components/doctorlists/api.ts:10`, `app/components/myday/useMyDay.ts:61-64`).
- **Impact.** About 15 minutes after sign-in, the next request from any staff member redirects them to the login screen. That includes a doctor mid-consult, a cashier mid-payment and a receptionist mid-registration. In-memory form state is lost. The consult note survives in IndexedDB, but Rx edits, check-in form fields and checkout line items do not. There is no `returnTo`, so after re-login the user lands on their role home rather than the record they were in. In practice this either forces staff to re-login all day or pushes pressure to lengthen the access TTL, which weakens security. The stored refresh token is dead weight that remains a theft target.
- **The idle lock compounds this.** It locks after 5 minutes (`app/IdleLockGuard.tsx:11`). A doctor who unlocks after 15+ minutes gets a 401 from `/auth/unlock`, which is reported as **"Incorrect PIN."** (`:57-59`) because any non-OK response is treated as a wrong PIN.
- **Security implication of `localStorage`.** Any XSS, including from a compromised future dependency, a browser extension or an injected script, can read both tokens. The 30-day refresh token gives persistent account takeover with access to PHI. There is currently no XSS sink (0 `dangerouslySetInnerHTML`), and React escapes by default, but there is also **no CSP** (`next.config.ts` is empty). The frontend is on Vercel (`nabd-nu.vercel.app`) and the API on Render (`.env.production`), so they are cross-site.
- **Recommendation.**
  1. Short term (days): one `apiClient` with single-flight refresh. Port `platformApi.ts:22-49`, which already does this correctly. Add a `returnTo` on forced logout.
  2. Target (weeks): a **same-origin BFF** using Next route handlers or `rewrites` under `/api/*` (see `node_modules/next/dist/docs/01-app/02-guides/backend-for-frontend.md`). It holds the refresh token in an `httpOnly; Secure; SameSite=Strict` cookie and returns only a short-lived access token in memory, or proxies with the cookie directly. Add CSRF protection for cookie-authenticated mutations, a strict CSP via `proxy.ts`/headers, and `Permissions-Policy`. This also lets the server render role-aware shells (`proxy.ts` optimistic checks).
  3. Clear the IndexedDB consult drafts (`nabd-consult-drafts`, `app/components/consult/draftStore.ts:8`) on sign-out. Today `signOut()` (`app/lib/session.ts:138-149`) and the idle lock's sign-out (`app/IdleLockGuard.tsx:72-86`) leave clinical text on shared workstations.

### 2.3 Error handling, loading and empty states

- **Strengths.** Most pages distinguish loading, forbidden (403), error with retry, and empty, using the shared `StateViews`. Several comments show careful thought about "unknown is not 'none'" (e.g. `app/consult/page.tsx:157`, where follow-up `undefined` is never shown as "Not set", and `app/components/myday/useMyDay.ts:16-17` `Part<T>` with an "unavailable" state).
- **Gaps.**
  - `submitPayment` has `try/finally` with **no `catch`** (`app/checkout/[queueEntryId]/page.tsx:242-266`). A network failure becomes an unhandled rejection with no message, while the server may already have recorded the payment.
  - Checkout uses its own plain "Loading…" and "Something went wrong." states instead of `StateViews` (`:268-270`).
  - Errors are flattened to strings. Field-level `Problem.errors[]` mapping exists only on login (`app/login/page.tsx:13-17`).
  - There is no global error boundary (§1.1) and no client error reporting (Sentry or OpenTelemetry).

### 2.4 Caching, polling, refresh

| Screen | Mechanism | Per tick | Evidence |
|---|---|---|---|
| Arrivals | `setInterval(load, 15 s)` | `/queue` + `/staff/roster` + one `/patients/{id}` per *uncached* patient + **2 per doctor** (`/queue/wait-estimate`, `/doctors/{id}/delay`) | `app/arrivals/page.tsx:47,229-282,341` |
| Nursing | `setInterval(load, 15 s)` | 6 list calls + **one `/patients/{id}` per entry with no cache** (priority patients fetched twice) + one `/clinical/vitals/{id}` per recorded row, in 4 sequential waves | `app/nursing/page.tsx:116,271-335,343` |
| Live queue board | `setInterval(load, 15 s)` | | `app/schedule/LiveQueueBoard.tsx:16,65` |
| My Day (doctor home) | **No polling.** Manual Refresh button. | `/queue` + 4 widgets + one `/patients/{id}` per distinct patient across queue and up to 100 appointments | `app/components/myday/useMyDay.ts:82-103`, `MyDay.tsx:38` |
| Consult queue | Load on open, no polling | one `/patients/{id}` per distinct patient + one `/departments/{id}/flow` per department | `app/consult/page.tsx:246-272` |
| Sidebar badges | Re-fetched on **every navigation**, no polling ("ponytail: no polling") | duplicates the page's own `/queue` | `app/ClinicNav.tsx:178-199` |

Nursing with 40 entries in mixed states sends roughly 80–100 requests every 15 s **per open tab**, and polling continues in background tabs (no `visibilitychange` handling anywhere). Five receptionists and three nurses at a mid-size clinic generate thousands of requests a minute for data that changes a few times a minute. The doctor's own home screen is the one screen that does not update when reception checks a patient in. There are no batch endpoints (`app/consult/page.tsx:247-248` notes "There is no batch endpoint yet").

### 2.5 Race conditions

- **Guarded.** Arrivals rows (`loadSeq`, `app/arrivals/page.tsx:137,229,259`), patients drawer (`openSeq`, `app/patients/page.tsx:176,281`), consult open-visit (`openSeqRef`, `app/consult/page.tsx:193,466-468`), and ClinicNav badges (`cancelled` flags).
- **Unguarded.** Arrivals `waitEstimates` and `delays` are set *after* the seq check (`app/arrivals/page.tsx:262-276`), so a slow older poll can overwrite newer values. Nursing `load()` has **no sequence guard at all**. Each poll's four sequential `setX` calls can interleave with a newer poll or a user-triggered reload (`app/nursing/page.tsx:313-320`). `useMyDay.load` has no guard, so a double-clicked Refresh can resolve out of order (`app/components/myday/useMyDay.ts:49-116`). There are only 2 `AbortController` uses in the whole app.
- **Arrivals patient cache.** `patientCache` (`app/arrivals/page.tsx:252-257`) never invalidates, so a name or MRN correction does not appear until a full page reload. It also grows without bound for the session.

### 2.6 Deduplication, optimistic updates, idempotency

- Request dedup exists only within one load (`byPatient` map, `app/consult/page.tsx:249-253`). Across components, the sidebar and the page fetch `/queue` independently.
- **Optimistic updates** are hand-written and narrow (e.g. `showStatus()` in `sendForVitals`, `app/arrivals/page.tsx:675`). Each one triggers a full reload afterwards.
- **No idempotency keys** on any mutation. `POST /patients` followed by `POST /queue/check-in` is two non-atomic calls, patched with `registeredRef` (`app/arrivals/page.tsx:362-365,429-466`). `POST /billing/invoices/{id}/payments` relies only on a disabled button (`app/checkout/[queueEntryId]/page.tsx:250-266,458`). A timeout followed by a retry can record a payment twice. Add an `Idempotency-Key` header to payments, check-in and registration, end to end.

### 2.7 Recommended data-fetching standard

Adopt **TanStack Query v5** behind a single typed client:

```
packages/api-client/
  openapi.d.ts          ← generated (openapi-typescript)
  client.ts             ← openapi-fetch instance: baseUrl, auth middleware, single-flight refresh, Problem parsing, Idempotency-Key
  queries/queue.ts      ← queryOptions({ queryKey: ['queue', {date, doctorId}], queryFn })
  queries/patients.ts   ← usePatients(ids) batched; staleTime 5 min
```

Rules:
- Query keys by resource.
- `staleTime` per resource: roster and departments for 10 min, patient demographics for 5 min, queue for 0 with push invalidation.
- `refetchOnWindowFocus: true`, and polling only as a fallback (`refetchInterval` paused when hidden).
- Mutations call `invalidateQueries(['queue'])` and use `onMutate` optimistic patches for status moves.
- `AbortSignal` is passed to fetch automatically, which removes the stale-response class of bugs.

This also deletes the 60 `Promise.resolve().then` effect workarounds.

### 2.8 Real-time approach for queue, arrivals, nursing and My Day

Use **Server-Sent Events**: one `GET /v1/events?topics=queue,nursing` stream per tab, authenticated, tenant-scoped, emitting small `{"type":"queue.entry.updated","id":…,"version":…}` invalidation events. Spring supports `SseEmitter`/WebFlux, and the backend already has an outbox poller (`application.yml` `outbox.poll-interval-ms: 2000`) that can fan out. On the client, map events to `queryClient.invalidateQueries` or `setQueryData`. Fall back to 30 s polling when the stream drops.
- SSE is the right choice over WebSocket here. Traffic is server→client only, SSE works through proxies, auto-reconnects with `Last-Event-ID`, and is simpler to secure.
- Cookie auth via the BFF solves `EventSource`'s lack of custom headers.
- Use `BroadcastChannel` so multiple tabs share one stream.
- Add **batch read endpoints** (`GET /patients?ids=…`, a queue response that embeds the patient summary). Real-time without batching just moves the N+1 problem.

---

## 3. State management, forms, type safety

### 3.1 State management

There is no state library. That is fine at this size, but local state is used for everything, including server cache, which is why the god components have 46–64 `useState` hooks. Cross-component state is limited to `AppShell` (sidebar collapse in `localStorage`, `app/AppShell.tsx:40-62`) and URL params (tab state on `/arrivals` and `/setup`, deep links on `/consult?entry=&step=`). Using the URL as tab state is good practice (`app/arrivals/page.tsx:702-706`). The recommendation is TanStack Query for server state, `useReducer` per complex workflow (login, check-in, consult close-out) and the URL for navigation state. Do not add Redux or Zustand unless a real cross-tree client state appears; offline outbox and multi-patient tabs are the likely candidates.

### 3.2 Forms and autosave

- **Validation.** It is imperative and per-handler (e.g. `app/arrivals/page.tsx:425-440`: "Pick a doctor.", "Fill in the new patient's name, phone and date of birth."). There are 81 `required` attributes, 16 `noValidate` and 9 `aria-invalid`. The one shared validator, `app/lib/vitals.ts` (mirroring the backend's `VitalsWriteRequest` and unit-tested), is the model to copy. There is no phone validation (only 2 `type="tel"`) and no E.164 normalisation for +91/+966/+971 numbers.
- **Recommendation.** Use React Hook Form with Zod schemas *generated from OpenAPI* (e.g. `openapi-zod-client`/`orval`). One `Field` component then wires label, hint, error, `aria-describedby` and `aria-invalid`.
- **Consult autosave (strength, `app/consult/page.tsx:325-418`).**
  - Local IndexedDB draft 800 ms after typing (`:78,333`).
  - Server PATCH every 10 s while dirty (`:77,404-412`).
  - Immediate retry on reconnect (`:415-418`).
  - Server-copy comparison before the first PATCH after a failure, with a conflict dialog (`:350-375`).
  - Never overwrites a signed note, and keeps orphaned local text visible (`:353-365`).
  - Refuses to save until the server copy has loaded (`:339`).
  - Refuses to switch patient while the save is failing (`:446-454`).

  This is better than most commercial EMRs. **Gaps:**
  1. Rx line items, allergies, orders and follow-up are *not* drafted, so only the SOAP note is protected.
  2. There is no `beforeunload` guard while dirty.
  3. The 15-minute token expiry (§2.2) defeats it: the save gets a 401 and the page navigates away.
  4. Drafts persist after sign-out (§2.2).
  5. The draft is plaintext PHI in IndexedDB. Encrypt with a per-session WebCrypto key held in memory, or accept the risk explicitly in the DPIA.
- **Keyboard flows in forms.** The drug combobox implements the full ARIA 1.2 combobox pattern (`app/components/consult/DrugCombobox.tsx:79-140`: arrows, Home/End, Enter, `aria-activedescendant`, live status). Elsewhere forms rely on native tab order. There are no Enter-to-submit shortcuts beyond native `<form>` behaviour and no Ctrl/Cmd+S for the note (§5).

### 3.3 Type safety

- **Strengths.** `strict: true` (`tsconfig.json`). **Zero `any`.** 311 type declarations. Discriminated unions are used well (`Part<T>`, `SaveState`, `Step`).
- **Weaknesses.**
  - Every API type is hand-written and unchecked. `await res.json()` returns `any`, so 288 call sites are effectively unchecked casts, plus 95 explicit `as X` casts.
  - Types drift. `QueueEntry` has `createdAt?`/`source?` in `components/consult/shared.ts:12` but required in `arrivals/page.tsx:22`. `PatientDetail` has three shapes (`components/consult/shared.ts:14`, `patients/page.tsx:21`, `arrivals/page.tsx:28`). There are 24 copies of `Problem`.
  - Status values are untyped `string` (`status: string`), although the domain is a closed enum.
- **OpenAPI.** `api/openapi.yaml` is OpenAPI 3.1 with 99 paths and 3,093 lines. The backend exposes about 271 `@*Mapping` routes, and many endpoints the UI calls are **missing from the spec**: `/auth/unlock`, `/queue/wait-estimate`, `/staff/roster`, `/clinical/notes/*`, `/clinical/conditions/due`, `/specialty/dental/*`, `/packages*`, `/appointments/callback-list`, `/departments/{id}/flow`. Codegen therefore requires the spec to become complete first.
- **Recommendation.** Generate the spec from code with springdoc-openapi in CI, diffed against the committed `api/openapi.yaml` (fail on drift). Then run `openapi-typescript api/openapi.yaml -o packages/api-client/openapi.d.ts` and use `openapi-fetch`, so paths, params, bodies and responses are all type-checked. Model enums (`QueueStatus`, `VisitSource`) as string-literal unions in the spec.

---

## 4. Permissions in the UI

- **Mechanism.** Permissions are read from the JWT's `permissions` claim, decoded client-side (`app/lib/session.ts:28-33`). `can(p) = isOwner() || permissions.includes(p)` (`app/lib/permissions.ts:4-6`). Gating happens in three places:
  1. Nav filtering (`app/ClinicNav.tsx:263-296`).
  2. Page-level `AccessGate` (`app/components/ui/AccessGate.tsx:20-25`), used on 11 pages, which mounts children *and their fetches* only when allowed.
  3. In-page booleans (58 `can("…")` calls). Example: `app/arrivals/page.tsx:367-371` builds a `grants` object.

  The wireframe rule "absent, not disabled" is enforced by the static check `scripts/check-permission-rendering.mjs`.
- **UI-only risk.** The code and comments consistently say the server is the authority (`app/lib/session.ts:37-38`, `app/platform/platformApi.ts:113-115`), and the backend uses `x-required-permission` per operation in the spec. **This review did not verify backend enforcement.** That is covered by the backend review, and every UI gate should be considered cosmetic until that review confirms server-side checks on every route. Specific UI-side risks:
  - **Stale grants.** Permissions come from the token, so a role change takes effect only at the next token mint. Combined with no refresh this is "at next login". With refresh in place it becomes ≤15 min, which is acceptable.
  - **Inconsistent gating.** 27 pages are not wrapped in `AccessGate` and instead handle a 403 after the fetch (e.g. `app/consult/page.tsx:237-239`). Those pages flash a skeleton and then the "no access" state, and they fire requests that are guaranteed to 403 (which pollutes audit logs).
  - **Owner shortcut.** `isOwner()` bypasses all permission checks client-side (`app/lib/permissions.ts:5`). That is fine only if the server applies the same rule.
  - **Hard-coded role mapping on the platform side.** `useOperatorAccess` (`app/platform/platformApi.ts:146-153`) derives capabilities from role *codes* (`super_admin`, `billing`) rather than permissions, which duplicates server RBAC logic in the client.
  - `check-permission-rendering.mjs` is a regex over `<button>` tags (`:30-37`). It cannot see `<a>`, components or conditional rendering. Replace it with Playwright role-matrix tests (§9).
- **Recommendation.** Use a `usePermissions()` hook from one session context (not `localStorage` reads on every render), `<Can permission>` and `<AccessGate>` everywhere, and a generated `Permission` string union from the backend's permission catalogue so typos fail at compile time.

---

## 5. UX and usability for time-critical work

### 5.1 Keyboard

- Global: only **Ctrl/Cmd+K and "/" focus patient search** (`app/TopBar.tsx:56-66`). There is no shortcut for walk-in (W), check-in, next patient, save note (Ctrl+S), sign Rx, add drug, open checkout, or switching between Arrivals and Queue. No shortcut help overlay exists (0 `aria-keyshortcuts`, 0 `<kbd>`).
- Queue rows on Arrivals are keyboard-operable (`tabIndex=0` with Enter/Space, `app/arrivals/page.tsx:856-858`). **Patient registry rows are mouse-only** (`app/patients/page.tsx:713` `<tr onClick>` with no `tabIndex` or key handler), so a keyboard-first receptionist cannot open a patient record from the list. The setup wizard's step chips use `role="button" tabIndex={0}` with **no key handler** (`app/setup/wizard/page.tsx:308`).
- **Recommendation.** Add a command palette (Ctrl+K) that also accepts actions ("walk-in", "check in Ahmed", "next patient"). Add single-key shortcuts scoped per screen, shown in tooltips and in a `?` overlay. Add Ctrl+Enter to submit dialogs and Ctrl+S for save-now in consult.

### 5.2 Click counts for key tasks (estimated from code, not timed)

| Task | Current path | Est. interactions | Improvement |
|---|---|---|---|
| Walk-in registration + token | Arrivals → "Walk-in & token" (`app/arrivals/page.tsx:811`) → search → "new patient" → name, phone, DOB, gender → **doctor (no default; `openModal` resets `doctorId` to "", `:397`)** → complaint → payment mode → Submit → token dialog → Print/Done | ~4 clicks + 6–7 fields | Default the doctor (last used, or the shortest wait from the already-loaded `waitEstimates`). Default payment to the clinic's most common mode. Accept DOB *or* age. Enter submits. Duplicate check as you type. Target ≤ 2 clicks + 4 fields. The TopBar "Register walk-in" action exists only on `/schedule` (`app/TopBar.tsx:20-23`). It should be global for `queue:create`. |
| Check-in of a booked patient | Patients → row "Check in" → `CheckInDialog` (doctor, payment, complaint) → Submit → Done | 3–4 clicks | One-click check-in from today's appointment list with the booked doctor preselected. |
| Start consult | My Day → row → preview dialog → "Start consultation" → workspace opens at "Reason" | 3 clicks | "Call next" button on My Day that starts the next eligible patient directly, plus keyboard N. |
| Write Rx (one drug) | Step rail "Plan" → add line → drug combobox (type + Enter) → sig slots → duration → (quantity auto) → Save → Sign → acknowledgment checkbox → Confirm | ~8–10 | Good structure, and favourite Rx sets exist (`FavouriteRxSetsPanel.tsx`). Make "Sign" save implicitly. Keep the ack for warnings only. |
| Checkout | Consult "Send to checkout" → reception → `/checkout/{id}` → pick charges by category → Generate invoice → method + amount → Record payment | ~6–8 | Prefill amount = balance due, Enter records it, and **print or share the receipt (missing, §5.5)**. |

The "< 90 s consult" target is realistic for a returning patient with a favourite set. The biggest risks to it are the 15-minute logout (§2.2) and the waterfall load when opening a consult (§8).

### 5.3 Responsiveness and touch

- Breakpoints at 1024 (tablet) and 430 (phone with bottom nav, `app/globals.css:143-152`, `app/ClinicNav.tsx:462`). Consult has had a responsive pass for 1024/768 (commit `012ef02`). `Dialog` becomes a bottom sheet on narrow screens (`app/components/ui/Dialog.tsx:35-37`). The coarse-pointer 44 px minimum is global.
- **Gaps.** Breakpoint sprawl (§1.6). Tables convert to cards via `data-label` on Arrivals (good) but not everywhere. 115 rules at 9–11 px text are too small for a tablet at arm's length. The 768–1024 band (iPad portrait, the most common clinic tablet) relies on the 1024 rule only.

### 5.4 Idle lock

`app/IdleLockGuard.tsx` locks after 5 minutes. The concept is good: an overlay so in-progress work is preserved. **Defects:**
1. **Bypassable by reload.** The lock state is React state only (`:18`), so pressing F5 or opening a new tab removes the lock while the token is still valid. Persist a `lockedAt` flag in `sessionStorage`/`localStorage` and broadcast it to all tabs via `BroadcastChannel`.
2. **Underlying page stays interactive to keyboard and AT.** There is no `inert` on the app root, and no `role="dialog"`/`aria-modal`. Tab moves through PHI-bearing controls behind the 97%-opaque scrim. Set `inert` on `.appShell` while locked.
3. The PIN input has no `<label>` or `aria-label` (`:102-109`).
4. Any non-OK unlock response, including 401 for an expired token and 429 rate limiting, says "Incorrect PIN." (`:57-59`).
5. The 5-minute timeout is not configurable per tenant or role. Doctors typing a long note do generate keydown events, but reading a lab report does not. Consider a per-clinic setting and a 30 s warning countdown.
6. The skip list drifted from `SHELL_SKIP_PREFIXES`.

### 5.5 Printing

- Rx print has a dedicated A4 sheet (`app/components/consult/rxPrint.module.css:223-240`), hides the shell, forces paper colours on the dark theme, and shows a print header with prescriber registration (`RxPrint.tsx:13`). Documents print exists (`app/documents/[id]/print/`). The token slip prints (`CheckInDialog.tsx:82`, `checkin.module.css:43`).
- **Missing:**
  - **Invoice and receipt printing.** There is no print or PDF action on checkout (`window.print` appears only in the three places above). For India this means a GST-compliant tax invoice. For KSA it means a ZATCA simplified tax invoice with the mandatory QR code (Phase 1 and 2). This is a launch blocker for GCC.
  - A5 and thermal-roll (80 mm) layouts, which many Indian clinics use for Rx and token slips.
  - A bilingual Arabic/English Rx layout (KSA expects Arabic drug instructions).
  - WhatsApp or SMS share as an alternative to paper. The backend has WhatsApp config (`application.yml:74`).

### 5.6 Offline tolerance

- The "Synced"/"Offline" pill (`app/TopBar.tsx:118-120`) is driven only by `navigator.onLine`. The code comment admits there is no sync queue (`:28-30`). "Synced" is therefore misleading: it shows green while saves are failing because the API is down or the Wi-Fi has no upstream. Rename it "Online", or drive it from the API client's real request health (or Next 16's `useOffline()`, which treats failed fetches as offline, `02-guides/offline-support.md:145`).
- Only the consult note tolerates going offline (§3.2). Check-in and payments fail with a message, which is the correct behaviour for non-idempotent writes that have no outbox. Recommendation: a read-only offline shell (PWA) that shows the cached today-queue, and an outbox *only* for the consult draft and vitals. Never queue payments offline.

---

## 6. Accessibility

**Strengths:**
- `Dialog` is a solid modal: `role="dialog"`, `aria-modal`, `aria-labelledby`, focus moved in and restored to the opener (or the parent dialog), Escape handled by the top of a stack, and a Tab trap (`app/components/ui/Dialog.tsx:31-117`). The care taken to avoid StrictMode focus-flicker is notable.
- The drug combobox follows ARIA 1.2 (§3.2).
- Status is never colour-only: every `StatusPill` has an icon and text (`app/components/ui/StatusPill.tsx:1-23`).
- 163 `htmlFor` across 267 `<label>`s, 149 live or alert regions (`role="alert"` on errors is consistent), 91 `<main>` landmarks, `aria-current` on nav and steps, an `srOnly` utility, `role="status"` skeletons with labels, reduced-motion support and 44 px coarse-pointer targets.

**Findings:**

| Issue | Evidence | Fix |
|---|---|---|
| Five hand-rolled dialogs without focus trap or restore, Escape on `window` with no stacking (an Escape in a nested picker closes the parent too) | `app/billing/page.tsx:349,418`; `app/schedule/page.tsx:439`; `app/schedule/NewAppointmentDialog.tsx:106`; `app/patients/RegistryDialogs.tsx:104`; `app/staff/page.tsx:723` (step-up overlay) | Migrate to `ui/Dialog`. |
| Idle lock not modal to AT, no `inert`, unlabelled PIN | `app/IdleLockGuard.tsx:90-109` | §5.4 |
| Mouse-only row and step controls | `app/patients/page.tsx:713`; `app/setup/wizard/page.tsx:308` | Use a `<button>`/link in the row, or add tabIndex and key handling. |
| Invisible focus: `outline: none` with no `:focus` replacement on the registry search and inputs | `app/patients/patients.module.css:45,415` (17 `outline: none` app-wide, 11 `:focus-visible` rules) | Add a global `:focus-visible` ring token. Lint the CSS with stylelint `a11y/no-outline-none`. |
| Contrast: `--nb-text-tertiary` #78848c on `--nb-surface-3` #1e262d = **4.0:1** (fails AA for normal text), used in 169 rules, often at 11–12 px. `--nb-text-disabled` on surface-1 = 2.5:1. | `app/globals.css:43-46` (computed) | Lighten tertiary to about #8b97a0 (≥4.5:1 on surface-3). Add `prefers-contrast: more` and a light theme for bright clinics. |
| No per-route `<title>`. Page changes are not announced. | `app/layout.tsx:14-17` | `metadata.title` template per route plus a route-announcer check. |
| `scope` on only 9 of 266 `<th>` | grep | Add `scope="col"`. Use a `Table` primitive. |
| Language of content: `lang="en"` fixed, no `dir`, Arabic patient names not marked `lang="ar"`/`dir="auto"` | `app/layout.tsx:21` | `<bdi>`/`dir="auto"` on user-generated names (§7). |
| No skip link to main content past the 12-item sidebar | grep | Add "Skip to content". |

There is no automated a11y testing. Add `@axe-core/playwright` to every E2E journey and `eslint-plugin-jsx-a11y` at "strict" (the Next preset includes only a subset).

---

## 7. Internationalisation and RTL

### 7.1 Current state

- **No i18n framework.** There is no next-intl, react-intl or i18next, no message catalogues, and no locale routing. `app/TopBar.tsx:26-28` confirms it: "no i18n… system exists anywhere in this app".
- **Hard-coded English strings.** A heuristic scan (JSX text nodes, user-facing attributes, `setError("…")`, `label:`/`title:` literals) finds **about 2,700**. Multi-line JSX and template literals are undercounted, so a realistic total is **3,000–4,000 strings**. The heaviest files are `nursing/page.tsx` (193), `packages/page.tsx` (163), `consult/page.tsx` (148), `setup/page.tsx` (139) and `arrivals/page.tsx` (131). Strings are often built by concatenation (```${n} of ${total} patients` ``, `app/patients/page.tsx:666`), which needs ICU plural and ordering in Arabic.
- **Date formatting.** Locales are mixed: `"en-GB"` (30 calls), `"en-CA"` (9, used as a YYYY-MM-DD hack), `undefined`/`[]` (device locale, 12), and no-arg (70). The same screen can show different formats on different machines.
- **Numbers and currency.** `lib/money.ts` is good in intent: INR with `en-IN` lakh grouping, SAR with a trailing ﷼ (`app/lib/money.ts:3-11`). It is bypassed in three places (§1.4), and it supports **only INR and SAR**. UAE (AED), Qatar (QAR), Oman (OMR, 3 decimals), Bahrain (BHD, 3 decimals) and Kuwait (KWD, 3 decimals) are unsupported, and the 2-decimal assumption is wrong for 3-decimal currencies. Money is a JS `number` with client `round2` preview maths (`app/checkout/[queueEntryId]/page.tsx:175-179`). The preview applies tax to the pre-discount subtotal and then subtracts the discount, which can disagree with the server's invoice (GST/VAT is normally charged on the discounted value). Treat the server as the only source of totals and show its quote.
- **Nice touch.** A Hijri date via `Intl.DateTimeFormat("en-SA-u-ca-islamic-umalqura")` (`app/nursing/page.tsx:242`).
- **Timezones: three inconsistent strategies.**
  1. The clinic's configured timezone, from the API (`app/schedule/page.tsx:319-351`, `NewAppointmentDialog.tsx:11`).
  2. A hard-coded region→zone map that **ignores the clinic's configured timezone** (`IN → Asia/Kolkata`, `KSA → Asia/Riyadh`), duplicated in `components/consult/DocumentSheet.tsx:5` and `FollowUpPanel.tsx:12`.
  3. **The device's local clock** (`useMyDay.ts:39-42` builds "today" from `new Date()`, `MyDayParts.tsx:149`, `MySchedule.tsx:125`).

  A Dubai or Bahrain clinic, or a doctor's laptop set to another zone, will query the wrong "today" around midnight and render times inconsistently. Pick one rule: **clinic timezone from the session or tenant profile, everywhere**, through one `formatClinicDate/Time` helper.
- **Font.** Poppins with the `latin` subset only (`app/layout.tsx:8-12`). Arabic and Devanagari fall back to `system-ui`, which looks inconsistent across Windows, Android and iPad and has poor Arabic metrics at 11–13 px.
- **Bug: non-ASCII JWT claims are corrupted.** `decodeAccessToken` does `JSON.parse(atob(payload))` (`app/lib/session.ts:18-26`, and the four other copies in §1.4). `atob` yields a byte string, so UTF-8 names are corrupted. I reproduced it: `"د. محمد"` decodes as `"Ø¯. ÙØ­ÙØ¯"`. Every Arabic, Hindi or accented `staffName` and `tenantName` (`AuthService.java:589-591`) shows corrupted text in the sidebar's clinic card and signed-in footer. Fix: `new TextDecoder().decode(Uint8Array.from(atob(b64), c => c.charCodeAt(0)))`. This must be fixed before any GCC pilot.

### 7.2 RTL readiness (strength)

The CSS is almost entirely logical already:

| Physical | Count | Logical | Count |
|---|---|---|---|
| `margin-left`/`-right` | **1** (`app/setup/departments/departments.module.css:79`) | `margin-inline*` | 19 |
| `padding-left`/`-right` | 0 | `padding-inline*` | 40 |
| `left:`/`right:` | 0 | `inset-inline*` | 17 |
| `border-left`/`-right` | 0 | `border-inline*` | 12 |
| `text-align: left` | 5 (`staff.module.css:359,369`, `departments.module.css:97,128`, `reports.module.css:105`) | `inset-block*` | 7 |
| inline-style physical props in TSX | 1 | | |

There is also an RTL-aware icon flip (`app/components/myschedule/mySchedule.module.css:29`). The remaining work is small:
- Replace six physical declarations.
- Mirror directional glyphs (the `›` chevrons in `app/overview/page.tsx:355-384`, and the arrow icons in `Icon.tsx`).
- Flip `translateY`-only animations (already safe).
- Check the bottom-sheet and drawer slide direction.
- Set `dir` on `<html>`.
- Use `dir="auto"`/`<bdi>` for mixed-script content (patient names, drug names, phone numbers, which must stay LTR inside RTL text).

### 7.3 i18n and RTL migration plan

1. **Foundation (1 sprint).**
   - Add **next-intl**. It is App Router-native and works in client components, and ICU messages handle Arabic plurals (zero, one, two, few, many, other).
   - Locales `en`, `ar`, `hi` (optionally `ml`/`ta`/`ur` later). The locale comes from a user preference, falling back to the tenant default, then the browser. Use a cookie, not URL segments, because this is an authenticated app and URL locales add no SEO value.
   - Set `<html lang dir>` in the root layout. This needs the BFF cookie or a pre-hydration inline script to avoid a direction flash (see `02-guides/preventing-flash-before-hydration.md`).
   - Fonts: `next/font` with **IBM Plex Sans Arabic** or **Noto Sans Arabic**, plus **Noto Sans Devanagari**, as fallback families after Poppins with `unicode-range` subsets.
   - Fix the `atob` UTF-8 bug.
2. **Formatting layer (in parallel).** `lib/format.ts` with `formatMoney(amount, currency, locale)` driven by ISO 4217 minor units, `formatDate/Time(iso, {tz: clinicTz, locale})`, `formatNumber` and phone formatting with libphonenumber-js. Add a lint rule banning raw `toLocale*String` and `new Intl.*` outside `lib/format.ts`. Decide whether Arabic UI uses Arabic-Indic digits (`ar-SA`) or Latin digits (`ar-SA-u-nu-latn`). Most Saudi clinical software uses Latin digits for clinical values, and that should be a clinic setting.
3. **Extraction (2–3 sprints, feature by feature).** Turn on `eslint-plugin-i18next` (`no-literal-string`) for JSX, migrate one feature folder per PR in priority order (Login, then Arrivals and Check-in, then Consult/Rx, then Checkout/Invoice, then the rest), and use namespaced keys (`arrivals.walkIn.title`). Clinical terms (SOAP labels, sig instructions) need clinician-reviewed Arabic, not machine translation.
4. **RTL QA.** Fix the six physical properties. Add stylelint `liberty/use-logical-spec` to keep it that way. Run a Playwright visual snapshot of every screen in `ar` and `en`.
5. **Bilingual documents.** Rx, invoice and certificates with an Arabic+English layout, and ZATCA QR on invoices.

---

## 8. Performance

- **Bundle.** The bundle is lean because there are no dependencies. The largest chunk is 229 KB (framework), and route chunks are about 40–160 KB uncompressed. Bundle size is *not* the problem today. Watch it when adding TanStack Query (~13 KB gz), next-intl (~15 KB gz) and RHF+Zod (~25 KB gz). Keep message catalogues per namespace and per route.
- **Network waterfalls dominate.** Every route renders an empty shell, then hydrates, then reads `localStorage`, then fetches the list, then fans out per row (§2.4). Opening `/consult` runs `/queue`, then N×`/patients/{id}`, then D×`/departments/{id}/flow` (`app/consult/page.tsx:236-272`). Opening a visit then loads the note, clinical context, vitals and more. On a 4G phone in a basement clinic this is 2–4 s to useful content. **LCP risk is high and INP risk is medium**: the 64-state Arrivals component re-renders fully on each poll, and the 1 s `setInterval` in `WaitlistPanel.tsx:86` re-renders that whole panel every second. **CLS** risk comes from skeleton-to-content swaps with different heights and from the sidebar badge counts arriving late.
- **Memoisation.** There are 2 `useMemo` and 0 `memo()` in the app. Filtering and sorting run inline on every render, which is fine at today's sizes. The real fix is smaller components and query-cached data rather than sprinkling `memo`.
- **Lists.** The patient registry is server-capped at 200 rows with an honest "showing first N" message (`app/patients/page.tsx:749-753`). It has no paging, so add cursor paging. Queue and arrivals lists are a day's worth (tens to low hundreds of rows), so virtualization is not needed yet. Revisit at 300+ rows with TanStack Virtual for the registry and the audit log (`app/platform/audit/page.tsx`).
- **Images.** None are used (0 `<img>`, 0 `next/image`). Icons are inline SVG. Good.
- **Fonts.** `next/font` self-hosts Poppins. Four weights of Latin is fine. Arabic and Devanagari will add about 100–200 KB, so subset them and use `display: swap`.
- **Server cost.** Polling fan-out (§2.4) is the largest performance cost, and it lands on the Spring backend and Postgres, not the browser.
- **Recommendations.** TanStack Query cache + SSE + batch endpoints. Prefetch the next patient's context on My Day hover/focus. Split god components. Add `web-vitals` reporting (Next's `useReportWebVitals`) to a RUM endpoint. Add a CI budget with `@next/bundle-analyzer` or `size-limit`.

---

## 9. Testing

**Current state:**
- `tests/unit/vitals.test.mjs`: 9 `node:test` cases for `app/lib/vitals.ts`. Good quality, well commented, and tied to the clinical sign-off reference.
- `scripts/test-consult-flow.mjs`: 8 cases for `consultFlow.ts`. They pass (I ran them), but **they are not wired into `npm run test:unit`**, whose glob is `tests/unit/*.test.mjs` (`package.json` scripts).
- `scripts/check-permission-rendering.mjs`: the regex check (§4).
- Nothing else: **no component tests, no E2E, no visual regression, no a11y tests, no contract tests.** The script itself notes "there's no Playwright/e2e harness in this repo yet" (`scripts/check-permission-rendering.mjs:3`).
- Safety-relevant pure logic is untested: `doseLogic.ts` (weight staleness and bounds for the dose calculator), `rxPadLogic.ts` (sig parsing and quantity suggestion), `pregnancyPolicy.ts`, `rxSets.ts`, `lib/money.ts`, `lib/session.ts#landingPathFor`, and `draftStore` conflict logic.

**Recommended pyramid:**

1. **Unit (Vitest, replacing `node:test`).** Move both scripts under `tests/unit` now. Target 90%+ coverage on `**/*Logic.ts`, `lib/*` and formatters. Add property tests (fast-check) for the sig parser and money rounding.
2. **Component (Vitest + React Testing Library + MSW, or Playwright Component Testing).** `Dialog` focus behaviour, `DrugCombobox` keyboard, `AccessGate`, consult autosave and conflict (with fake timers and an IndexedDB shim), check-in duplicate review. MSW handlers generated from OpenAPI (`msw-auto-mock`) keep mocks honest.
3. **E2E (Playwright, against Docker Compose backend + Postgres with seed tenants for IN and KSA).** Top journeys:
   1. Login with MFA, then landing per role (doctor → My Day, nurse → Nursing, reception → Arrivals, owner → Overview).
   2. Walk-in registration with duplicate detection, then token, then the queue board updates.
   3. Nurse captures vitals, then status becomes `vitals_done`.
   4. Doctor starts consult, writes the note (with offline/online toggle and autosave), adds Rx, handles a warning override, signs, prints, sends to checkout.
   5. Checkout: invoice, partial plus full payment, receipt, then day close.
   6. Idle lock, unlock, token expiry and refresh mid-consult (a regression test for §2.2).

   Also add a role-permission matrix: for each seeded role, assert the visible nav and that restricted actions are *absent*, not disabled. This replaces the regex script. Run with `@axe-core/playwright` on every page, plus a 375 px phone project, a 768 px tablet project and an `ar` locale project.
4. **Visual regression.** Playwright `toHaveScreenshot` per screen × {en, ar} × {desktop, tablet, phone}, or Chromatic if a Storybook is added for `ui/`. Mask times and tokens.
5. **Contract.** Generated types catch compile-time drift. Add runtime checks too: run Schemathesis or Dredd against the backend from the OpenAPI spec in backend CI, and have MSW in frontend tests reject requests that do not match the spec (`openapi-backend` validator). Run the springdoc diff (§3.3) on every PR.

CI order: `next typegen` → `tsc` → `eslint` → `vitest` → `next build` → `playwright` (sharded). Block merges on all of them.

---

## 10. Mobile strategy

**Context.** A fast **patient** app is planned soon. Staff use phones and tablets today (doctors especially). The codebase is all TypeScript and React, with design tokens already in CSS variables, no web-specific state library, and pure logic modules (`consultFlow.ts`, `rxPadLogic.ts`, `lib/vitals.ts`, `lib/money.ts`) that are framework-free.

**Patient app: recommend React Native with Expo (SDK current), Expo Router, EAS Build/Update.**
- **Shared code.** In a monorepo (pnpm + Turborepo) the patient app and staff web share `@nabd/api-client` (generated OpenAPI types + `openapi-fetch` + TanStack Query hooks, which are identical on RN), `@nabd/domain` (status enums, formatters, validators, the pure logic above), `@nabd/i18n` (the same ICU catalogues, with RN's `I18nManager` for RTL) and `@nabd/tokens` (Style Dictionary JSON that emits CSS variables for web and a TS theme for RN). Realistically 30–40% of non-UI code is reusable from day one, rising once the API client exists.
- **Why not Flutter.** It needs a second language and toolchain, no type or client sharing with the TS codebase (a Dart client would have to be generated separately), and a separate i18n pipeline. Flutter's rendering quality is excellent, but for a small team already fluent in React and TS the duplication cost outweighs it.
- **Why not PWA-only for patients.** iOS web push needs home-screen install, which most patients will not do. App-store presence matters for trust in GCC and India healthcare. Biometric login, secure storage (Keychain/Keystore for refresh tokens, unlike `localStorage`), deep links from WhatsApp reminders, and calendar or wallet integration are all native strengths. Expo's OTA updates (EAS Update) keep release speed close to the web's.
- **Patient app MVP scope:** OTP login (the backend already has `/auth/otp/*`), book/reschedule, live queue position and wait estimate (reuse the SSE stream with a patient-scoped topic), prescriptions and documents (read-only PDF), invoices and payments (Razorpay/UPI for IN; mada/Apple Pay/STC Pay for KSA), family profiles (guardian model exists), Arabic/English/Hindi.
- **Prerequisite.** The backend needs patient-scoped auth, endpoints and a separate token audience. Do not reuse staff tokens or permissions.

**Staff use: make the staff web an installable PWA, not a separate native app (for now).**
- Add a web app manifest and icons (`public/` holds only `hello.txt` today), and a service worker that caches *only the app shell and static assets*, never API responses with PHI. Add standalone display for tablets at reception and in consult rooms, Web Push for "patient ready" alerts to doctors (`02-guides/progressive-web-apps.md`), and `useOffline()` for honest connectivity UI.
- Staff workflows are already responsive (1024/430 breakpoints, bottom nav, bottom-sheet dialogs). A PWA delivers 90% of the native value for staff at about 5% of the cost.
- Revisit a native staff app only if there is a hard need for offline-first ward rounds, barcode or NFC scanning, or MDM-managed shared devices. If so, the same Expo stack and shared packages apply.

---

## 11. Recommended target structure

```
nabd/
├─ apps/
│  ├─ web/                         # this Next.js app
│  │  ├─ app/
│  │  │  ├─ (auth)/login, accept-invite, owner/login
│  │  │  ├─ (clinic)/layout.tsx    # clinic shell, SessionProvider, QueryClientProvider, IntlProvider, IdleLock
│  │  │  │  ├─ arrivals/page.tsx   # thin: <ArrivalsScreen/>
│  │  │  │  ├─ consult/…
│  │  │  │  └─ error.tsx, not-found.tsx
│  │  │  ├─ (platform)/platform/…  # operator console shell
│  │  │  ├─ api/[...path]/route.ts # BFF: cookie session → backend; refresh; CSRF
│  │  │  └─ global-error.tsx
│  │  ├─ proxy.ts                  # optimistic auth redirect, CSP nonce, locale cookie
│  │  └─ features/
│  │     ├─ arrivals/  {components/, hooks/useArrivals.ts, walk-in/ (form+schema), arrivals.test.tsx}
│  │     ├─ consult/   {note/ (editor, autosave, draftStore), rx/, orders/, timeline/, logic/*.ts}
│  │     ├─ nursing/ patients/ checkout/ billing/ schedule/ my-day/ setup/ staff/ reports/ platform/
│  │     └─ shell/     {ClinicNav, TopBar, CommandPalette, IdleLock}
│  └─ patient/                     # Expo app (later)
├─ packages/
│  ├─ api-client/   # openapi.d.ts (generated), client.ts (auth, refresh, Problem, Idempotency-Key), queries/*, sse.ts
│  ├─ domain/       # enums, status machines, vitals/rx/dose logic, validators (zod), formatters
│  ├─ ui/           # Button, Field, Input, Select, Combobox, Dialog, Drawer, Table, Tabs, Toast, StatusPill, StateViews, Icon (+ Storybook)
│  ├─ tokens/       # Style Dictionary source → css vars (web) + TS theme (RN); breakpoints; type scale; z-index
│  ├─ i18n/         # ICU catalogues en/ar/hi per namespace; format helpers (tz-aware)
│  └─ config/       # eslint (jsx-a11y, i18next, import boundaries), tsconfig, stylelint (logical props)
└─ e2e/             # Playwright: journeys, role matrix, axe, visual (en/ar × desktop/tablet/phone)
```

Rules:
- Pages are thin.
- A feature may import from `packages/*` and its own folder, never from another feature. Enforce this with `eslint-plugin-boundaries`.
- `packages/ui` has no data fetching.
- All network access goes through `api-client`.
- All formatting goes through `i18n`/`domain`.

---

## 12. Findings table

Severity: **Critical** = data loss, security or legal exposure, or blocks a market. **High** = major reliability, UX or scalability issue. **Medium** = notable quality or maintainability issue. **Low** = polish.
Effort: S ≤ 2 days, M ≤ 2 weeks, L > 2 weeks (one engineer).

| ID | Severity | Area | Finding | Evidence | Recommendation | Effort |
|---|---|---|---|---|---|---|
| FE-01 | **Critical** | Auth / data loss | Clinic app never refreshes the 15-min access token. Every 401 redirects to `/login`, losing in-progress forms. Refresh token is stored but unused. | `application.yml:59`; `app/consult/page.tsx:212-216`; only `platform/platformApi.ts:30` refreshes | Single API client with single-flight refresh (port `platformApi.ts:22-49`) and `returnTo` | S |
| FE-02 | **Critical** | Security | Access and 30-day refresh tokens in `localStorage`. No CSP or security headers. Cross-site API. | `app/login/page.tsx:45-46`; `next.config.ts` empty; `.env.production` | Same-origin BFF with httpOnly cookie refresh, CSRF, CSP via `proxy.ts` | M |
| FE-03 | **Critical** | Dependencies | `next@16.3.1` within critical advisory range (RCE, cache poisoning, SSRF) | `npm audit --omit=dev` | Upgrade to ≥16.4.2. Add Dependabot/Renovate and an audit gate. | S |
| FE-04 | **Critical** | i18n | Non-ASCII JWT claims corrupted by `atob` (Arabic or Hindi staff and clinic names render as mojibake) | `app/lib/session.ts:18-26` + 4 copies; reproduced | TextDecoder-based base64url decode in one helper | S |
| FE-05 | **Critical** (GCC/IN launch) | i18n | No i18n framework. ~3–4k hard-coded strings. `lang="en"`, no `dir`. Latin-only font. | `app/layout.tsx:8-12,21`; `app/TopBar.tsx:26-28` | next-intl, locale cookie, Arabic and Devanagari fonts, extraction plan (§7.3) | L |
| FE-06 | **Critical** (KSA/IN) | Billing UX | No invoice or receipt print/PDF/share. ZATCA QR and GST invoice layout absent. | `window.print` only in `RxPrint.tsx:159`, `CheckInDialog.tsx:82`, `documents/[id]/print/page.tsx:66` | Invoice print route (A4 + 80 mm), ZATCA QR, bilingual, WhatsApp share | M |
| FE-07 | High | Data layer | No shared API client: 32 `API_BASE` copies, ~20 `authedFetch` copies, 6 fetch styles | §1.4 | `packages/api-client` + TanStack Query | M |
| FE-08 | High | Performance / backend load | 15 s polling with N+1 fan-out (nursing ~2N+6 req/tick, no cache). Polls in hidden tabs. | `app/nursing/page.tsx:271-343`; `app/arrivals/page.tsx:229-282,341` | SSE invalidation + batch endpoints + `visibilitychange` pause | M |
| FE-09 | High | Real-time UX | Doctor's My Day never auto-updates. Sidebar badges refresh only on navigation. | `app/components/myday/useMyDay.ts:118`; `app/ClinicNav.tsx:178-199` | Subscribe to SSE queue topic | S (after FE-08) |
| FE-10 | High | Reliability | Race conditions: nursing load and My Day have no sequence guard; arrivals estimates set after the guard | `app/nursing/page.tsx:271-335`; `useMyDay.ts:49`; `app/arrivals/page.tsx:262-276` | Query library (abort + keyed cache). Interim: seq refs. | S |
| FE-11 | High | Payments | No idempotency keys. `submitPayment` lacks `catch`, so a timeout plus retry can double-charge silently. | `app/checkout/[queueEntryId]/page.tsx:242-266` | `Idempotency-Key` header (backend too), catch with "check status" recovery | S |
| FE-12 | High | Security / UX | Idle lock bypassable by reload or new tab. Page behind it not `inert`. Expired token shows "Incorrect PIN". | `app/IdleLockGuard.tsx:18,57-59,90-109` | Persist and broadcast lock state, `inert` root, distinct error states, per-tenant timeout | S |
| FE-13 | High | Architecture | Seven god components (835–1,489 LOC, 29–64 `useState`) | §1.3 | Feature folders, extract hooks and pure logic, reducers for workflows | L |
| FE-14 | High | Types | Hand-written API types drift (4× `QueueEntry`, 3× `PatientDetail`, 24× `Problem`). 288 untyped `res.json()`. OpenAPI covers ~99 of ~271 routes. | `components/consult/shared.ts:9` vs `arrivals/page.tsx:19`; `api/openapi.yaml` | springdoc-generated spec + drift gate, then `openapi-typescript` + `openapi-fetch` | M |
| FE-15 | High | Testing | One 64-line unit test. Consult-flow test not wired. No component, E2E, a11y, visual or contract tests. Safety logic (dose, sig) untested. | `package.json` test glob; `scripts/test-consult-flow.mjs` | Vitest + RTL + MSW; Playwright journeys + role matrix + axe (§9) | L |
| FE-16 | High | Timezone | Three timezone strategies (clinic tz, hard-coded region map, device clock) | `FollowUpPanel.tsx:12`; `DocumentSheet.tsx:5`; `useMyDay.ts:39-42` | One clinic-tz formatting helper from the session | S |
| FE-17 | High | Data protection | Consult drafts (PHI) persist in IndexedDB after sign-out on shared workstations. Plaintext. | `draftStore.ts:8`; `lib/session.ts:138-149`; `IdleLockGuard.tsx:72-86` | Clear on sign-out, optional WebCrypto session key | S |
| FE-18 | Medium | Money | Four currency formatters. INR/SAR only. 2-decimal assumption (wrong for KWD, BHD, OMR). Client tax preview applies tax pre-discount. | `lib/money.ts:3-11`; `billing/page.tsx:111`; `checkout/[queueEntryId]/page.tsx:175-179` | One ISO-4217-aware formatter. Server-quoted totals. | S |
| FE-19 | Medium | App Router | 38 of 40 pages client-only. No `error.tsx`/`not-found.tsx`. Single static title. | §1.1 | Route groups, error boundaries, per-route metadata; server shells after the BFF | M |
| FE-20 | Medium | A11y | 5 hand-rolled dialogs without focus trap or restore | `billing/page.tsx:349,418`; `schedule/page.tsx:439`; `NewAppointmentDialog.tsx:106`; `RegistryDialogs.tsx:104` | Migrate to `ui/Dialog` | S |
| FE-21 | Medium | A11y | Mouse-only patient rows and wizard steps. Invisible focus on registry inputs. | `patients/page.tsx:713`; `setup/wizard/page.tsx:308`; `patients.module.css:45,415` | Real buttons/links, global `:focus-visible` token, stylelint | S |
| FE-22 | Medium | A11y | `--nb-text-tertiary` 4.0:1 on surface-3 (AA fail) in 169 rules, 115 rules at 9–11 px. Dark-only theme. | `globals.css:43-46` | Adjust token, minimum 12 px, light / high-contrast theme | S–M |
| FE-23 | Medium | UX speed | Few keyboard shortcuts (only search). Walk-in has no default doctor. "Register walk-in" only on `/schedule`. | `TopBar.tsx:20-23,56-66`; `arrivals/page.tsx:397` | Command palette + scoped shortcuts, smart defaults | M |
| FE-24 | Medium | UX honesty | "Synced" pill reflects `navigator.onLine`, not API health or sync state | `TopBar.tsx:28-30,118-120` | Drive from API client health or `useOffline()`. Rename to "Online". | S |
| FE-25 | Medium | Forms | Imperative validation per handler. No schema or phone validation. Only the SOAP note is drafted (not Rx, orders, check-in). No `beforeunload`. | `arrivals/page.tsx:425-440`; `consult/page.tsx:325-418` | RHF + generated Zod, `Field` component, draft all consult sections | M |
| FE-26 | Medium | Design system | `ui/` lacks Button, Field, Input, Table, Tabs, Toast. Global CSS patches missing states. 134 inline styles. | `globals.css:101-108`; `IdleLockGuard.tsx:91-121` | Build `packages/ui` + Storybook | M |
| FE-27 | Medium | CSS | Breakpoint sprawl (9 values). Undefined tokens (`--nb-radius-sm` without fallback). No type-scale or z-index tokens. | `consultParts.module.css:89`; §1.6 | Token package with breakpoints, type and z-index; custom media | S |
| FE-28 | Medium | Permissions | Gating mixes `AccessGate` (11 pages) with post-fetch 403 handling. Regex-only contract check. Grants are stale until token re-mint. Platform derives capabilities from role codes. | `components/ui/AccessGate.tsx`; `scripts/check-permission-rendering.mjs:30-37`; `platformApi.ts:146-153` | `usePermissions` context, typed permission union, Playwright role matrix | M |
| FE-29 | Medium | CI | `tsc` fails standalone because `LayoutProps` needs `next typegen` | `fe-tsc.log`; `app/layout.tsx:19` | `next typegen && tsc --noEmit` in CI | S |
| FE-30 | Low | RTL | 6 physical CSS declarations and unmirrored `›` chevrons remain | `departments.module.css:79,97,128`; `staff.module.css:359,369`; `reports.module.css:105`; `overview/page.tsx:355-384` | Logical equivalents + stylelint rule | S |
| FE-31 | Low | Hygiene | 60 `Promise.resolve().then` effect workarounds. Duplicated helpers. Boilerplate README. Drifted skip lists. | §1.4; `IdleLockGuard.tsx:13` vs `session.ts:126` | Goes away with FE-07, FE-13 | S |
| FE-32 | Low | Mobile | No PWA manifest or icons. No web push for "patient ready". | `public/` contains only `hello.txt` | Manifest + shell-only SW + push (§10) | S–M |

---

## 13. Strengths to preserve

- **Clinical-safety thinking in code.**
  - Never overwrite a signed note (`consult/page.tsx:353-365`).
  - Never save before the server copy is known (`:339`).
  - "Unknown" never shown as "none" (`:157`, `useMyDay.ts:16`).
  - Suggested Rx quantity is labelled and never silently changed (`RxPad.tsx:11-15`).
  - Weight staleness is "unknown, never fresh" (`doseLogic.ts:15-19`).
  - Vitals plausibility limits mirror the backend and are tested.
- **Two-tier consult autosave with conflict resolution** (IndexedDB + PATCH + server-compare).
- **Token discipline** and **near-complete logical-property CSS**, which put RTL much closer than the absence of i18n suggests.
- **Accessible primitives:** `Dialog`, `DrugCombobox`, `StatusPill` (icon + text), `role="alert"` errors, reduced motion, 44 px coarse-pointer targets, responsive bottom nav and sheets.
- **Zero `any`**, strict TS, a lean zero-dependency bundle, a clean ESLint run.
- **The operator console's `platformApi.ts`** already shows the right pattern: single-flight refresh, centralised storage keys, problem parsing. Generalise it.
- Commit messages and comments trace to ticket IDs (NB-, DOC-, NUR-, PLT-), which makes this codebase unusually auditable.

## 14. Suggested sequencing

1. **Week 1 (stabilise).** FE-01, FE-03, FE-04, FE-11, FE-12, FE-17, FE-29. Wire the consult-flow test.
2. **Weeks 2–4 (foundation).** FE-02 (BFF), FE-14 (spec + codegen), FE-07 (api-client + TanStack Query), FE-16, FE-18. Playwright scaffold with journeys 1–4.
3. **Weeks 4–8 (live + speed).** FE-08/09/10 (SSE + batch endpoints), FE-23 (shortcuts, defaults), FE-20/21/22 (a11y), FE-06 (invoice print + ZATCA). Start FE-13 (split arrivals and consult first).
4. **Weeks 6–12 (market readiness).** FE-05 i18n (Login, Arrivals, Consult, Checkout first), RTL QA (FE-30), `packages/ui` + tokens (FE-26/27), PWA (FE-32). Monorepo packages ready for the Expo patient app.
