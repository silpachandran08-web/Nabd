# PSE brief: build-ready stories for the Nabd Engineering Workbench

You are one of ten Principal Software Engineers (PSEs). Together you turn everything the team has learned into **build-ready work items** that a group of engineers will pick up and deliver hand in hand, using a shared tracker app (the "Workbench"). Each of you owns one domain. Your output is ONE JSON file that the app loads.

The founder's words: "proper human readable task and sub task tracker with proper requirement against each persona, mapping the right microservice, with most details specified — interface, database, which microservice, acceptance criteria".

## Inputs (read what your domain needs; cite them)

| What | Where |
|---|---|
| Tracker tasks T-001..T-384 (JSON export) | `/tmp/claude-0/-home-user-Nabd/b7763512-fd1b-5a01-8814-88ca57a93dd5/scratchpad/workbench/tracker_tasks.json` |
| 1,165 traced requirements (FRD, founder brief, old plan) | `/tmp/claude-0/-home-user-Nabd/b7763512-fd1b-5a01-8814-88ca57a93dd5/scratchpad/workbench/requirements.json` |
| Target architecture, ADRs, module layout, findings MF-xx, sprints | `/home/user/Nabd/docs/engineering/06-target-architecture-and-remediation-plan.md` |
| Backend / security / data / frontend / QA reviews | `/home/user/Nabd/docs/engineering/01..05-*.md` |
| Identity, consent, exchange design (DDL, flows, APIs) | `/home/user/Nabd/docs/engineering/07-consent-and-data-sharing-architecture.md` |
| Personas, journeys, MoSCoW backlog, time targets | `/home/user/Nabd/docs/strategy/03-personas-interviews-requirements.md` |
| Roadmap, phases, epics EP01..EP42 | `/home/user/Nabd/docs/strategy/05-gap-analysis-and-product-roadmap.md` |
| Market / regulation | `/home/user/Nabd/docs/strategy/01-market-competitors-regulation.md` |
| Current code (what exists; reuse table/endpoint names) | `/home/user/Nabd/backend/src/main/java/com/nabd/hms/**`, `/home/user/Nabd/db/migrations/*.sql`, `/home/user/Nabd/api/openapi.yaml`, `/home/user/Nabd/frontend/app/**` |
| Prototype (the UX to build; screen ids `role.view`) | `/home/user/Nabd/prototype/js/core.js` (NB.ROLES nav) and `/home/user/Nabd/prototype/js/modules/*.js` |
| Founder's FRD | `/tmp/claude-0/-home-user-Nabd/b7763512-fd1b-5a01-8814-88ca57a93dd5/scratchpad/ref/uploads/Clinic_SaaS_IN_KSA_FRD_v1.xlsx` |

## Personas (use these ids exactly)

`reception` Receptionist · `doctor` Doctor · `nurse` Nurse/triage · `solo` Solo doctor (no front desk) · `ward` Ward nurse (IPD) · `er` Emergency team · `radiology` Radiologist/radiographer · `hospops` Hospital services staff · `pharmacist` Pharmacist (in-clinic or standalone) · `lab` Lab technician/pathologist · `billing` Billing & insurance desk · `owner` Owner/admin (clinic owner up to chain COO, incl. compliance/DPO) · `patient` Patient · `caregiver` Family member/caregiver · `platform` Nabd platform/ops team · `engineering` Internal engineering (only for pure technical work with no user-facing persona — prefer a real persona whenever someone benefits).

## Service catalogue (use these ids exactly in `service` and `module`)

Deployables (ADR-01: modular monolith + four services, no more):
- `core` — Nabd Core, Spring Boot modular monolith (system of record). Modules: `shared`, `iam`, `organization`, `patient`, `scheduling`, `visit`, `clinical`, `nursing`, `billing`, `pharmacy`, `identity`, `consent`, `exchange`, `reporting`, `realtime`, `worker`, `platform`. New modules allowed where the domain needs them, named: `lab`, `imaging`, `emergency`, `inpatient` (wards, OT), `hospital-services` (blood bank, CSSD, dietary, stores, MRD, roster, fleet), `insurance`, `specialty`, `engagement` (recall, reviews, feedback), `hr`.
- `comms-gateway` — WhatsApp/SMS/email send + webhooks, templates, delivery, DLT. Modules: `whatsapp`, `sms`, `email`, `templates`, `webhooks`, `bot`.
- `patient-edge` — public, high-traffic patient-facing API + WhatsApp booking bot logic + aggregator + branded booking pages; holds no PHI beyond what it relays. Modules: `booking`, `aggregator`, `patient-auth`, `bot-flows`, `branded-sites`.
- `clinical-intelligence` — Python CDS/AI service (rules first, LLM suggestions with guardrails). Modules: `rules`, `knowledge-base`, `suggestions`, `evaluation`.
- `control-plane` — Nabd's own operator plane: tenants, provisioning, plans/entitlements, regions, support access, billing of clinics. Modules: `tenants`, `provisioning`, `plans`, `regions`, `support`, `subscriptions`.
- Frontends: `staff-web` (Next.js staff app incl. BFF), `patient-web` (secure links, consent approvals, aggregator site), `patient-app` (Expo, gated Phase 3).
- `infra` — regional stacks, CI/CD, observability, Valkey/Redpanda, KMS. Modules: `ci`, `observability`, `regions`, `security`, `data-platform`.

Events: transactional outbox → Valkey Streams (Redpanda later); CloudEvents envelope; names `<module>.<entity>.<verb>.v1` (e.g. `visit.checked_in.v1`). Real-time UI via SSE topics from `core.realtime`.

## Output schema (write valid JSON; this is what the app renders)

File: `/tmp/claude-0/-home-user-Nabd/b7763512-fd1b-5a01-8814-88ca57a93dd5/scratchpad/workbench/specs/<DOMAIN>.json`

```json
{
  "domain": "ACC",
  "domain_name": "Patient access, booking, queue & ETA",
  "owner_squad": "Patient Access",
  "summary": "2-4 sentences: what this domain delivers and why it matters",
  "epics": [
    {
      "id": "ACC-E01",
      "title": "Booking rules engine",
      "goal": "one sentence outcome",
      "phase": 1,
      "stories": [
        {
          "id": "ACC-001",
          "title": "Receptionist accepts or declines a pending booking in one tap",
          "persona": "reception",
          "other_personas": ["patient"],
          "story": "As a receptionist I want … so that …",
          "context": "why this matters, the rule set, edge cases in plain English (3-8 sentences)",
          "priority": "P0|P1|P2|P3",
          "phase": 1,
          "sprint": "S4 or empty",
          "estimate_days": 8,
          "service": "core",
          "module": "scheduling",
          "also_touches": ["comms-gateway/whatsapp", "staff-web"],
          "depends_on": ["PLT-004"],
          "tracker_refs": ["T-161"],
          "source_refs": ["EP10", "FRD BOOK-03", "MF-22", "B-07"],
          "prototype": ["reception.requests"],
          "acceptance": [
            "Given a clinic in Hybrid mode, when a returning patient books a follow-up on WhatsApp, then the booking is confirmed instantly and a token is issued",
            "… 4-8 testable Given/When/Then criteria, with numbers and time targets where they exist …"
          ],
          "api": [
            { "method": "POST", "path": "/v1/booking-requests/{id}/accept", "summary": "Accept a pending request",
              "auth": "permission scheduling:review; tenant-scoped", "request": "{ slotId?: UUID, note?: string }",
              "response": "200 { appointmentId, token, status: 'CONFIRMED' }", "errors": ["409 SLOT_TAKEN", "410 REQUEST_EXPIRED"],
              "idempotent": true }
          ],
          "db": [
            { "op": "create_table|alter_table|add_index|add_policy|migrate_data", "object": "booking_requests",
              "detail": "columns (id uuid pk, tenant_id uuid not null, …, status text check in (…), expires_at timestamptz); FORCE RLS policy tenant_id = ANY(app.tenant_ids); index (tenant_id, status, expires_at)",
              "schema": "public|identity|consent", "migration": "V1xx__booking_requests.sql" }
          ],
          "events": [
            { "name": "scheduling.booking_request.accepted.v1", "direction": "publishes|consumes",
              "payload": "{ tenantId, requestId, appointmentId, patientRef }", "consumers": ["comms-gateway", "core.realtime"] }
          ],
          "ui": [ { "app": "staff-web", "feature": "requests", "screen": "reception.requests", "notes": "keyboard: A accept, D decline; optimistic update; SSE refresh" } ],
          "nfr": ["p95 accept < 300 ms", "audit log row per decision", "no PHI in WhatsApp body"],
          "tests": ["integration: accept races → exactly one confirmation (409 for loser)", "e2e J2: WhatsApp booking → reception accept < 60 s"],
          "risks": "optional short note",
          "subtasks": [
            { "id": "ACC-001.1", "title": "Migration: booking_requests table + RLS + index", "type": "db", "estimate_days": 1, "detail": "…" },
            { "id": "ACC-001.2", "title": "Accept/decline endpoints with optimistic lock", "type": "backend", "estimate_days": 2, "detail": "…" },
            { "id": "ACC-001.3", "title": "Publish accepted/declined events via outbox", "type": "backend", "estimate_days": 0.5, "detail": "…" },
            { "id": "ACC-001.4", "title": "Requests inbox UI with one-tap actions", "type": "frontend", "estimate_days": 2, "detail": "…" },
            { "id": "ACC-001.5", "title": "Integration + e2e tests", "type": "test", "estimate_days": 1, "detail": "…" }
          ]
        }
      ]
    }
  ],
  "tracker_coverage": { "T-161": ["ACC-001"] },
  "open_questions": ["…"]
}
```

Subtask `type` ∈ `db`, `backend`, `api`, `frontend`, `mobile`, `integration`, `ai`, `test`, `infra`, `security`, `design`, `docs`, `data`.

## Rules (quality bar)

1. **Every tracker task in your scope must appear in at least one story's `tracker_refs`.** Fill `tracker_coverage` (task → stories). If a tracker task belongs to another domain, list it under `open_questions` as "belongs to <DOMAIN>" instead of dropping it.
2. **One story = one deliverable a persona notices** (or one technical capability for `engineering`), sized 2–15 days. Split bigger work. Typically 3–7 subtasks each, summing roughly to the story estimate.
3. **Be specific and grounded.** Use real table, endpoint and class names from the current code where they exist (say "extend table `queue_entries`" rather than inventing a parallel one). New endpoints follow `/v1/...` REST, Problem+JSON errors, `Idempotency-Key` on money/state-changing POSTs, cursor pagination. Every tenant table: `tenant_id`, FORCE RLS, `tenant_id = ANY(...)` policy form (ADR-07). PHI never in logs, caches without tenant keys, or WhatsApp bodies.
4. **Acceptance criteria are testable**: Given/When/Then, with the time targets from doc 03/05 (booking < 60 s, walk-in < 45 s, Rx < 90 s, ETA ±10 min for 80%, etc.) and market variants (India, UAE, KSA, Qatar) where behaviour differs.
5. **Personas are real**: a story's `persona` is who uses or benefits from it; put pure plumbing under `engineering` but say which persona it unblocks in `context`.
6. **Respect phases and decisions**: phases 0–5 from doc 05/06; P0 = before any real patient data. Where a founder decision is open (Decisions sheet, DEC-xx), note it in `risks` and design so either answer works.
7. **Prototype links**: list the screen ids that show the UX (check they exist in core.js NB.ROLES nav, e.g. `doctor.consult`, `owner.workflows`).
8. **Volume**: aim for 25–60 stories for your domain depending on its tracker load; quality over count. Total estimate should be in the same ballpark as the tracker effort for your scope (state it in `summary`).
9. Write plain English a new engineer understands. No marketing language.
10. Validate your JSON with `python3 -c "import json;json.load(open(PATH))"` before finishing. Do not modify any repo file. No git commands.
