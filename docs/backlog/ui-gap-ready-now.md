# UI items that can be worked on now

Generated 2026-10-11 from `ui-gap-backlog.csv`. 103 of 303 items are **Ready now**: frontend-only, every API they need exists today, no open design decision, no prerequisite item. Readiness was assessed by reviewers from the backend controllers and the backlog text, so spot-check the API note before starting. Dev size: S <1 day, M 1-3 days, L >3 days for one developer on the first usable slice.

Cross-cutting rows (RTL, responsive, shell/navigation, permission gating) repeat across roles: build the shared piece once, then use the role rows as checks.

## Platform operator (11)

| Finding | Prio | Sev | Size | Title | What exists / first slice |
|---|---|---|---|---|---|
| PLT-002 | P1 | Major | M | Navigation is a flat 8-link list, wireframe has 12 items in 6 sections with icons | Group, icon, rename and reorder the existing 8 SURFACES in surfaces.ts/PlatformNav; new surfaces added later. Region/install badge source unverified. |
| PLT-005 | P1 | Major | M | Write actions are gated only by :view; read-only roles still see forms and action buttons | GET /platform/auth/me returns role code; gate forms/buttons on role and show read-only note for commercial. |
| PLT-006 | P1 | Major | S | Discount Approve/Reject shown to Billing and Commercial; wireframe restricts to Super admin | Gate Approve/Reject on role == super_admin using /me role; relabel pending as Awaiting super admin. |
| PLT-017 | P1 | Major | S | Lifecycle transitions use window.prompt for the reason | Replace window.prompt with confirm modal and reason textarea; transition endpoint already takes reason. |
| PLT-015 | P2 | Minor | S | One-time invite links shown outside wireframe, 'Run provisioning' loops steps client-side | Copy button, masked state and in-memory handling are frontend only; response has no expiry field, so use static expiry copy. |
| PLT-019 | P2 | Minor | S | Currency display: MRR is not split/aggregated by market; formatting is toFixed(2) with code suffix | Shared Intl.NumberFormat money helper; data already carries currency and cents. |
| PLT-023 | P2 | Minor | S | Territories subtitle claims independent stacks but wireframe says residency is not a sales territory | Copy-only retitle and wireframe note. |
| PLT-028 | P2 | Minor | M | Audit log rows lack clear before/after rendering and export | Date range params and before/after fields exist; build diff drawer, date filter and client CSV. Actor filter and audited export need server later. |
| PLT-029 | P2 | Minor | M | Status colour tokens missing: platform pills use hard-coded amber/rgba | Add status tokens in globals.css and shared StatusPill; values taken from wireframe. |
| PLT-035 | P2 | Minor | M | Loading, empty and error states are generic one-liners | Skeletons, Retry buttons and role-denied cards are frontend only. |
| PLT-037 | P2 | Minor | S | Provisioning, billing, plans and others duplicate authedFetch and 401 handling | Extract duplicated authedFetch and 401 refresh into app/lib/platformApi.ts. |

## Owner / Manager (24)

| Finding | Prio | Sev | Size | Title | What exists / first slice |
|---|---|---|---|---|---|
| OWN-001 | P1 | Major | L | Sidebar has no icons, no tablet rail, no mobile bottom nav; collapse hides the sidebar completely | Icon sprite, 72px rail, tablet rail and mobile bottom nav are pure frontend in ClinicNav; icons to be drawn from wireframe. |
| OWN-002 | P1 | Major | S | Plan chip above the user card (owner only) is missing | Plan name present in subscription summary; chip links to Setup subscription tab until Plan route exists. |
| OWN-003 | P1 | Major | M | Clinic & branch switcher (and owner 'switch clinic') absent | Workspace list and select endpoints exist; build popover and owner sign-out redirect. |
| OWN-005 | P1 | Major | S | Owner top-bar primary action 'Start day close' not present | Add owner top-bar link to /billing day-close dialog gated on billing:view/approve. |
| OWN-009 | P1 | Major | S | Activation / go-live checklist card (SCS-03) missing from Overview | Checklist endpoint exists; render card on overview with deep links. Dismiss persistence unspecified. |
| OWN-022 | P1 | Major | S | Tab 7 'Punctuality' (RCP-04) is not a tab; rendered inside Staff tab | Move punctuality card to a 7th tab and fix headers; endpoint exists. |
| OWN-023 | P1 | Major | S | 'Today's money' tab lacks tender-count tiles, Day-close card and stat tones | Day-close status card and stat tones ready; per-tender counts absent from MoneyResponse (later). |
| OWN-033 | P1 | Major | M | Invite flow is an always-open inline form; wireframe uses header buttons + modals | Move invite form into a modal behind an Invite staff header button; Clone role exists. |
| OWN-034 | P1 | Major | M | Staff table columns/actions differ: no Restriction, Login method, Offboard, status icon; extra Scope/Last acti | Restriction (scope/fieldGrants) and last active (lastSeenAt) available; login method and Offboard action need backend later. |
| OWN-035 | P1 | Major | M | Permission matrix: wrong module list and action set; roles shown as a table, not a matrix | Align module list and show read-only matrix for selected role from grants; plan-locked P cells need entitlement data later. |
| OWN-054 | P1 | Major | S | Owner/reception actions not gated by permission; 'Sell package' (reception) and owner-only edits visible to al | Gate package actions and tabs on JWT permissions packages:create/edit/approve. |
| OWN-056 | P1 | Major | S | /owner/login is unreachable from the UI and has no MFA/authenticator step | Add Owner sign-in link on /login now; MFA step needs backend since owner login returns only pendingToken. |
| OWN-006 | P2 | Minor | S | User menu: 'Region' row and support-session footer missing | Region row from decoded identity is ready; support-access status line has no tenant-side endpoint (later). |
| OWN-007 | P2 | Minor | M | Semantic tokens diverge from DESIGN.md; pages hard-code their own colours | Move healthcare state tokens into globals.css and replace per-page literals. |
| OWN-015 | P2 | Minor | M | Loading / error states are bare text; no skeletons, no retry | Skeleton tiles and Retry button are frontend only. |
| OWN-017 | P2 | Minor | S | KPI tiles: 'UPI' label is hard-coded; KSA should read 'Mada & digital'; no icons; refunds tile wording | Region-aware tender labels from identity.tenantRegion over tender method codes; refunds data absent (see OWN-019). |
| OWN-021 | P2 | Minor | S | Day-close actions gated with absence (correct) - but only billing:approve is checked; owner view-only roles st | Gate Charges & Taxes link on setup:view from JWT permissions. |
| OWN-031 | P2 | Minor | S | Reports sub-sections fetched/rendered with no per-action permission check | Hide export controls unless reports:export is in JWT permissions. |
| OWN-036 | P2 | Minor | S | Role presets side panel and helper note missing | RoleResponse has no user count; derive from staff list client-side and add the hidden-not-greyed note. |
| OWN-040 | P2 | Minor | S | Header carries 8 nav buttons (Arrivals, Nursing Worklist, Patients, Consultation, Packages, Staff, Owner Insig | Remove dev shortcut buttons, keep Setup wizard button and rename title. |
| OWN-042 | P2 | Minor | M | Clinic profile: no region lock indicator, branch, branding, or Save feedback; free-text specialties | Region lock badge and specialties chips possible from profile; branch and logo have no fields (later). |
| OWN-047 | P2 | Minor | S | Payroll export lacks month picker, Export CSV button and PF/ESI (GOSI for KSA) note | Payroll export accepts month param; add month picker, client CSV export and statutory note copy. |
| OWN-057 | P2 | Minor | S | Workspaces page: brand/clinic list OK but lacks branch/region chips, last-opened, search and a tenant-switch f | Search and return path are frontend; branch/region chips depend on ClinicSummary fields (unverified). |
| OWN-060 | P2 | Minor | S | Account & Security page not in wireframe nav (Extra) and reachable only from Staff header / nav 'My account' f | Add Account & Security entry to owner user menu linking /account. |

## Receptionist (28)

| Finding | Prio | Sev | Size | Title | What exists / first slice |
|---|---|---|---|---|---|
| REC-026 | P0 | Blocker | M | Appointment cancel / move: Read-only InfoDialog | Cancel (reason required) and reschedule endpoints exist; gate on queue:edit. Move reason and resend reminder not in API, so first slice is cancel/move. |
| REC-035 | P0 | Major | S | Allergies claim: Shows "No known allergies" even if the backend withholds data (backend behaviour unverifie | Remove the no-known-allergies claim when clinical:view is absent; frontend only (server withholding unverified). |
| REC-007 | P1 | Major | L | Responsive: Zero media queries in nav and arrivals CSS | Pure CSS/layout breakpoints for nav, worklist, drawers; no API involved. Large but frontend-only. |
| REC-013 | P1 | Major | M | Row actions: Rows not clickable; inline buttons only | Row click opening patient/visit rail uses existing patient detail endpoint. |
| REC-014 | P1 | Major | S | Vitals actions: Record vitals, Send for vitals and Mark priority shown to all | Gate vitals actions on JWT permissions (clinical:edit) in frontend; permission already exists. |
| REC-017 | P1 | Major | M | Check-in confirmation: Plain call and refresh | Token (tokenNumber) and wait estimate exist; build result panel with print. Package suggestion deferred. |
| REC-019 | P1 | Major | M | Walk-in duplicates: Weak duplicate list, no guardian capture | Match-score review and guardian step supported: PatientMatchCandidateResponse.matchScore, PatientWriteRequest.guardianId, confirmedNotDuplicate. |
| REC-028 | P1 | Major | M | Waitlist: None; backend WaitlistController exists | WaitlistController exists with create, list, accept, delete; build panel and offer-slot flow. |
| REC-030 | P1 | Major | M | Live Queue: Reuses the Arrivals table | Per-doctor columns can group GET /v1/queue by doctorId in the frontend. |
| REC-034 | P1 | Major | M | Patient drawer clinical content: Drawer shows clinical sections and write forms without guards | Gate drawer sections on clinical:view from JWT; frontend only. |
| REC-039 | P1 | Major | L | Merge workflow: Filter only; backend has merge and undo | Merge and undo endpoints exist (patients:delete); build gated merge UI. |
| REC-045 | P1 | Major | S | Payment methods: Raw cash/card/upi/other values | Label cash/card/upi/other with a config-free label map; backend vocabulary fixed. |
| REC-050 | P1 | Major | S | Charges & Taxes link: Link shown unconditionally | Gate link on setup:view from JWT. |
| REC-056 | P1 | Major | M | Sell package: 6-step wizard with eligibility, tax allocation, digital card (pksell L11500) | Sell endpoint exists; build wizard with eligibility from instances; tax allocation display may need data. |
| REC-003 | P2 | Minor | S | Nav badges: Badge uses float:right | Flex chip layout plus Arrivals count from queue list is frontend-only. Checkout badge needs pending-bill count and a Checkout item (later). |
| REC-008 | P2 | Minor | S | Tokens: 28 tokens in globals | Consolidate globals onto shared --nb-* tokens; frontend only. |
| REC-012 | P2 | Minor | S | Status pills: Lowercase text pills without icon | Shared StatusPill with icon is frontend-only. |
| REC-016 | P2 | Minor | S | Quick-action bar: Equal-weight buttons | Action hierarchy and merging token into walk-in are layout changes only. |
| REC-025 | P2 | Minor | S | Arrivals states: Bare loading/error; no role="dialog" | Retry, dialog semantics, error mapping are frontend work; batch endpoint is optional. |
| REC-036 | P2 | Minor | S | Phone masking: List masks; drawer shows full phone | Apply the existing list masking in drawer with a reveal control. |
| REC-038 | P2 | Minor | S | Duplicate match score: List without score | matchScore exists; show score; matched-fields detail absent so show score only. |
| REC-040 | P2 | Minor | S | Registry search: Search on blur; no pagination or coverage badge (token search unverified) | Debounced search and paging via registry/PatientPage; coverage badge may lack data. |
| REC-041 | P2 | Minor | S | Check-in dialogs: Two divergent dialogs (Arrivals and Patients) | Share one check-in component across Arrivals and Patients; frontend refactor. |
| REC-052 | P2 | Minor | S | OTC entry: Counter sale button on Arrivals ungated | Gate counter sale on existing billing:create/pharmacy permission in frontend. |
| REC-053 | P2 | Minor | S | Money format: Locale currency formatting | Intl.NumberFormat helper only. |
| REC-054 | P2 | Minor | S | Prescribed drug names: Banner lists prescribed drug names | Show count only from existing data; frontend change. |
| REC-061 | P2 | Minor | S | Financial flags: Balance filter and tag shown regardless | Gate balance filter and tag on billing:view from JWT. |
| REC-062 | P2 | Minor | S | Per-action permissions: Arrivals quick actions and counter sale ignore per-action permissions | Gate each quick action on its permission (queue:create/edit etc.) in frontend. |

## Doctor (17)

| Finding | Prio | Sev | Size | Title | What exists / first slice |
|---|---|---|---|---|---|
| DOC-021 | P0 | Major | M | Pinned clinical safety strip (allergy severity, chronic, last visit, pregnancy) | Sticky safety strip can read allergies (severity), conditions and timeline last-visit from existing endpoints; pregnancy flag source not verified but can be omitted. |
| DOC-023 | P0 | Major | M | "Reason" and vitals are not shown to the doctor | Backlog says UI-only: queue entry has presentingComplaint; vitals GET returns values and abnormalFlags. |
| DOC-029 | P0 | Major | M | "Complete consultation" skips the Close step and its safety checks | Close panel, unsigned-Rx warning and retry handoff are frontend over existing prescription status and queue status PATCH; checkout handoff partial-failure behaviour uses existing calls. |
| DOC-014 | P1 | Major | M | Offline drafts, sync conflict and autosave failure are not handled | Autosave error strip, IndexedDB draft keyed by queueEntryId and updatedAt conflict compare are client-side; note/prescription GET/PATCH exist (updatedAt in NoteResponse to confirm). |
| DOC-019 | P1 | Major | M | Queue list is missing type, clinical flags, wait, scope filter and per-state actions | Columns, Mine/All filter, Summary action and priority pill use QueueEntryResponse (priorityReason, priority, createdAt); flags use allergies/conditions endpoints; Summary action fully lands with DOC-024 but column work is independent. |
| DOC-024 | P1 | Major | M | Pre-consult summary | Summary overlay composes existing timeline, prescriptions, vitals and allergies endpoints before status change. |
| DOC-025 | P1 | Major | L | Workspace structure: five-step flow, visit timeline, today's-patients list, sticky action bar | Layout restructure and URL step state are frontend; data is all existing. Large overlap with other consult items, sequence first. |
| DOC-034 | P1 | Major | S | Signing a prescription fails silently and has no confirmation | Surface problem detail on sign failure and add confirmation summary; sign endpoint exists and returns problem+json. |
| DOC-038 | P1 | Major | M | Follow-up creation: shortcut intervals, order and reminder, versus a late date picker booking a fixed 10:00 sl | Interval chips plus real slot via availability endpoint and clinic timezone are frontend. Order/reminder state is not backed, so build the booking slice only. |
| DOC-045 | P1 | Major | M | Clinical edit controls are rendered for any role with patient view | Compute canEdit from token permissions and hide forms; permissions in session.ts. |
| DOC-011 | P2 | Minor | M | Sidebar lacks icons, "More" overflow, badges and responsive rails | Icons, collapsed rail, mobile nav and badges are pure CSS/React in ClinicNav; badge counts can use existing GET /queue. |
| DOC-015 | P2 | Minor | S | Token coverage and status pills (icon + text, shape cues) | Tokens, StatusPill and Drawer components are pure frontend design-system work. |
| DOC-020 | P2 | Minor | S | Loading / empty / error states of the queue | Skeleton, Retry and empty-state copy are frontend-only on GET /queue. |
| DOC-037 | P2 | Minor | S | "Previous medicines" is names only | Previous prescriptions already loaded with dose/sig/duration fields; only rendering changes. |
| DOC-044 | P2 | Minor | S | Workspace labels and button copy | Shared label map and copy changes are frontend only. |
| DOC-051 | P2 | Minor | M | Dental / specialty tools are not reachable from the consult "Examine" step | Open dental chart in a drawer from consult keyed by active patient; chart endpoints exist and DOC-009 hub not required. |
| DOC-052 | P2 | Minor | S | Patient registry and drawer for the doctor | Role filter sets and Start-consultation from drawer using existing registry and queue data. |

## Nurse (13)

| Finding | Prio | Sev | Size | Title | What exists / first slice |
|---|---|---|---|---|---|
| NUR-014 | P0 | Major | S | Urgent/flagged state is conveyed by colour (left border) only; chips below the 11px floor | Icon plus text urgent badge, logical CSS properties, chip font size are frontend only. |
| NUR-023 | P0 | Major | M | Validation: blank/implausible entries are accepted; blank save advances the visit | Required-value, range and systolic>diastolic validation are frontend; backend ranges may be hard-coded client-side. |
| NUR-018 | P1 | Major | M | Loading/error handling replaces the whole worklist (and any open dialog) and has no retry | Keep last good data, inline retry, skeleton rows; frontend only. |
| NUR-041 | P1 | Major | M | Nursing page does no client-side permission gating | Gate nursing actions via getPermissions() in lib/session.ts. |
| NUR-042 | P1 | Major | M | Patient drawer shows full phone number and relies on API omission for money fields | Mask phone in drawer in frontend; server omission of balance is a verify step, not a blocker. |
| NUR-043 | P1 | Major | M | No tablet layout: no breakpoints, 248px sidebar always, sub-44px touch targets | Tablet breakpoint, rail nav, 44px targets are CSS only; shared nav change may overlap REC-007. |
| NUR-010 | P2 | Minor | S | Page title/subtitle differ; no day/Hijri line | Title, live count and date line are frontend; Hijri formatting via Intl. |
| NUR-015 | P2 | Minor | S | Clinical-flag semantics differ (presenting complaint rendered as 'urgent') | Render complaint as neutral info; reserve urgent for priority flag; frontend only. |
| NUR-020 | P2 | Minor | S | Empty state is text only; filters share one message | Per-filter empty copy; frontend only. |
| NUR-026 | P2 | Minor | S | Button and title copy differ | Copy alignment and Mark urgent secondary action; status PATCH/priority exist. |
| NUR-028 | P2 | Minor | S | Priority tab: no flagged-by / acknowledged-by names, seconds-resolution times, queue-status as state | Resolve staff names from roster, format times; ack state from priorityAcknowledgedBy. |
| NUR-035 | P2 | Minor | S | Consent/state presentation differs; blocked-until-consent not shown | ProcedureOrderResponse has consentSignedName/At; disable Start until consent; version number absent. |
| NUR-044 | P2 | Minor | S | Token conformance mostly good; a few hard-codes and physical directions | Add --nb-urgent token and logical properties. |

## Pharmacist (9)

| Finding | Prio | Sev | Size | Title | What exists / first slice |
|---|---|---|---|---|---|
| PHA-002 | P1 | Major | M | Dispensing queue and stock are reachable only through Setup, and the Setup page loads all-or-nothing | Move queue and items to own route fed by /pharmacy/* and make Setup tab load per tab; frontend refactor only. Route exposure depends on persona decision but page itself is independent. |
| PHA-005 | P1 | Major | M | No way to adjust stock, edit price or deactivate an item | PATCH /pharmacy/items/{id} exists but needs the full PharmacyItemWriteRequest; FE sends all fields from the loaded row. Stock stepper may need its own concurrency-safe call. |
| PHA-013 | P1 | Major | M | Setup wizard Pharmacy step has no choices | Wizard step renders three choices and PATCHes settings with defined mode mapping. |
| PHA-004 | P2 | Minor | S | Queue hides dosage, frequency, duration and instructions | Dosage, frequency, duration and instructions are already in DispensingQueueEntryResponse.Item; render only. |
| PHA-007 | P2 | Minor | S | "The hard rule" matrix and per-mode descriptions are missing | Hard-rule matrix card and per-mode description strings are static copy. |
| PHA-015 | P2 | Minor | S | Phase 2/3 previews absent (FEFO, controlled register, expiry alerts, refill reminders) | Backlog says nothing now; keep explicit not-available copy (no work). |
| PHA-016 | P2 | Minor | S | Actions are not hidden by permission | Gate actions on pharmacy:edit and billing:create via getPermissions. |
| PHA-017 | P2 | Minor | S | Not responsive at the checkout grid | Breakpoints for checkout/OTC layout in CSS modules. |
| PHA-019 | P2 | Minor | S | Missing empty and feedback states in the pharmacy tab | Empty and feedback states in pharmacy tab are frontend only. |

## Caregiver (1)

| Finding | Prio | Sev | Size | Title | What exists / first slice |
|---|---|---|---|---|---|
| CGV-007 | P2 | Minor | S | Guardian row states in the patient drawer | Guardian row states in drawer use existing guardianId, guardianName and guardianConsentGrantedAt fields. |
