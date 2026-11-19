# Offline check-in and billing: design (PLT-031)

**Status: design spike, nothing built in the product.** This document is the Phase 1 design for T-211 (Phase 2 offline billing; EP25, SYS-22, NB-328, B-92, NFR-12). A prototype of the sync rules and a replay simulation back it (section 10). It still needs the review in section 12.

Every statement about GST or ZATCA law in this document is marked **NEEDS EXPERT CHECK**: it is the author's reading, not advice, and a chartered accountant (India) and a ZATCA-qualified adviser (KSA) must confirm it before T-211 starts.

## 1. The problem
A receptionist or pharmacist in a tier-2 town loses the internet for minutes or hours. Patients keep arriving and paying. Today the app shows an offline pill and stops: there is no offline write queue (`TopBar.tsx` says so) and the service worker caches only the shell (`public/sw.js`, ADR-12). Phase 2 must let the desk and the pharmacy counter keep working, and when the link returns there must be no duplicate token, no duplicate bill, no lost payment and no silent overwrite.

## 2. Scope
| Works offline (Phase 2) | Stays online only | Why |
|---|---|---|
| Check-in of a booked or walk-in patient, with a provisional token | Booking, moving or cancelling appointments | Slots are a shared resource; holding one offline double-books |
| Desk bill for a visit and its payment: cash, or card/UPI taken on a stand-alone terminal with the reference typed in | Gateway payments, payment links | Need the provider |
| Pharmacy counter sale of non-prescription items, with payment | Rx-only and controlled items, dispensing against a prescription | The Rx gate, the quantity ledger and the controlled register are server checks (PHL-001, PHL-020); a safety rule, not a convenience |
| Quick registration of a new walk-in (name, phone) as a provisional patient | Patient merge, edits to an existing record | Duplicate and household rules run on the server |
| Reading today's list, the patient search index and the price list from the local copy | Clinical notes, prescriptions, vitals, documents | Not needed at the desk; keeps clinical data off the device |
| | Discounts that need approval, refunds, voids, credit notes, package sessions, insurance | Each needs a single-use server approval or holds a shared resource |

Anything not in the left column shows "needs internet" and is absent from the offline screens, not disabled.

## 3. What exists today
| Building block | Where | Gap for offline |
|---|---|---|
| Idempotency-Key on POST (PLT-026) | `shared/idempotency/IdempotencyFilter`, `IdempotencyStore` | Key is per (tenant, staff member); rows live 24 hours; only 2xx answers are stored; a crash after commit leaves an `in_progress` row that is taken over after 5 minutes and the action runs again. Not enough on its own (section 6.3) |
| `change_seq` on synced tables (OPS-006, T-131) | V2603, V2604; `shared/ETags` | Columns and triggers only. No delta endpoint, no tombstones, and the sequence value is taken before commit (section 6.4) |
| Gapless invoice, credit-note and receipt numbers (FIN-006) | `organization/series/InvoiceSeriesService`, V1920 | Allocated inside the committing transaction on the server; one series per tenant, kind and financial year |
| Token numbers | `queue/QueueService`, `QueueRepository.nextTokenNumber` | `max + 1` under a per doctor-day lock; `UNIQUE (tenant, doctor, date, token_number)` |
| Encrypted local drafts (FEF-005, FEF-035) | `staff-web/app/components/consult/draftStore.ts` | AES-GCM in IndexedDB, wiped on sign-out. The key sits in `sessionStorage` (a recorded shortcut) |
| Desk exception list (ACC) | `/v1/front-desk/exceptions`, V2740 | The natural place to show sync conflicts; needs new kinds |
| Staff PIN | `auth/PinChangeController` | Can unlock a locked offline device |
| Shell service worker (FEF-048) | `staff-web/public/sw.js` | Does not cache the desk routes or any data |

## 4. Design in one page
1. **Lease.** While online, a registered device holds an *offline lease*: a signed, short-lived grant bound to the device, the staff member, the facility and a snapshot of their permissions. It carries a device code (one letter, unique in the facility), the invoice and receipt number blocks the device may use, and a seed for the local encryption key.
2. **Local copy.** The device keeps a small encrypted copy of what the desk needs, kept fresh by a delta feed on `change_seq`.
3. **Commands.** Every offline action is a command with an `opId` (UUID v7 made on the device). It is written to an encrypted outbox in IndexedDB together with any counter it used, in one IndexedDB transaction, before anything is printed.
4. **Replay.** On reconnect the outbox is replayed, oldest first, to the normal endpoints. `opId` is sent as the `Idempotency-Key` and is also stored on the created row under a unique constraint (`client_op_id`). A command leaves the outbox only after a definite answer.
5. **Conflicts.** The server never silently drops or rewrites an offline action. What it cannot apply cleanly it records and flags, stores a conflict row, and a person decides.
6. **Limits.** A lease expires at the end of the clinic day or after 12 hours, whichever is first, and a number block is small. An offline device stops issuing bills when either runs out.

## 5. Provisional tokens
- Offline, the device cannot know the doctor's next token. It issues a **provisional token**: device code + its own counter for the clinic day, for example `A-07`. The slip prints it with the word "provisional" in the staff language. The counter lives in the same IndexedDB transaction as the check-in command.
- On reconnect the check-in is replayed to `POST /v1/queue/check-in` with the offline envelope (section 6.2). The server creates the queue entry, gives it the next real `token_number` for the doctor and day (the existing rule and constraint stay), and stores the provisional token on the entry.
- **The provisional token stays the patient-facing token for that visit** (board, voice call, WhatsApp "your turn"). The patient holds a slip that says `A-07`; renumbering it after sync would send people to the desk to ask. The real number is internal. *Decision D-1 for the Patient Access lead.*
- **Order of calling** follows arrival time, not token number: the entry's arrival time is the device's `occurredAt`, clamped to the lease window. A patient checked in offline at 10:02 and synced at 10:40 is not pushed behind those who arrived at 10:30. The ACC-012 call-order policy already works from arrival and appointment times; the board queries that sort by `token_number` need the same order (story 6).
- Until sync the doctor's screen does not show the patient. The desk must say so: the offline banner states that doctors cannot see offline arrivals yet. If the outage lasts, the desk works from the printed day list (existing downtime procedure, training item `task.offline`).
- The same patient checked in twice (two desks offline, or one offline and one online): section 7, rule C1.

## 6. Sync protocol
### 6.1 Lease
`POST /v1/sync/leases` (online, renewed every few minutes while the link is healthy) returns: lease id, device code, facility, staff id, permission snapshot, `validUntil`, number blocks (section 8), the delta cursors and the key seed. Renewal is safe to repeat: the server only ever raises a block's ceiling, and a lost answer leaves the device with its older, lower ceiling. The lease is signed by the server; the device shows it back with every replayed command.

A device whose local data was lost (storage cleared, browser reinstalled) never gets its old series back: it is registered again with a new device code, and the old block goes to reconciliation (section 8.3).

### 6.2 Up: commands and the outbox
A command is `{ opId, type, occurredAt, leaseId, body }`. Offline-made things are referred to by the `opId` that made them (a payment names its bill's `opId`, a bill names its check-in's `opId`), never by a server id the device cannot know yet.

Replay goes to the **existing** endpoints (`/v1/queue/check-in`, `/v1/billing/checkout/{queueEntryId}`, `/v1/billing/otc-checkout`, `/v1/billing/invoices/{id}/payments`), extended with the offline envelope, so validation, permissions, audit and events are the same code as online. New endpoints are only the lease, the delta feed and the conflict list.

Rules for the device:
- Serialise the body **once**, at enqueue, and resend those exact bytes. PLT-026 hashes method, path, query and body; a re-serialised body with the same key is answered `422 IDEMPOTENCY_KEY_REUSED`.
- Send oldest first. Do not send a command before the commands it builds on have a definite answer.
- A command leaves the outbox **only** on a definite answer: 2xx (including a replayed 2xx), or a final refusal.
- Keep and retry, with backoff and jitter, on: timeout, network error, 5xx, `409 IDEMPOTENCY_IN_PROGRESS`, `409 DEPENDENCY_NOT_APPLIED`, `401` (sign in again), `423 TENANT_SUSPENDED`, `429`.
- A final refusal the device does not understand (for example a validation error after a version change) moves the command to a **parked** list. Parked commands are never deleted by the app; they are shown as "needs support" and can be exported. A payment must never disappear because the server said 400.
- A new app version must still replay an old outbox (command bodies are versioned).

### 6.3 Server guards
| Guard | What it stops |
|---|---|
| `Idempotency-Key` = `opId` (PLT-026, unchanged) | The common retry within 24 hours by the same staff member: the stored answer is returned |
| `client_op_id` unique on `queue_entries`, `invoices`, `invoice_payments`, stock movements and provisional patients | A re-run PLT-026 cannot catch: after 24 hours (a weekend outage), by a different signed-in staff member, or after the 5-minute takeover of a row whose bookkeeping failed. The handler finds the row and answers success again |
| `409 DEPENDENCY_NOT_APPLIED` | A command that arrives before the one it builds on (requests can overtake each other). Retried, not a conflict |
| Number checks (section 8) | A number outside the leased block, or one already used by another `opId` |
| Full re-validation | The device is not trusted: permissions as of the lease, facility scope, and the bill total recomputed from its lines. A total that differs is recorded as issued and raised as conflict C8 |
| `occurredAt` clamp | Device clocks are wrong or changed. The server stores the device time, clamped to the lease window, and its own receive time. The lease carries server time so the device can correct its clock from a monotonic timer |

Every offline command is audited with `offline = true`, device, lease, `occurredAt` and receive time.

### 6.4 Down: delta sync on `change_seq`
`GET /v1/sync/changes?scope=…&since=…` returns rows of one scope with `change_seq` above the cursor, in order, plus the next cursor. The device applies a row only if its `change_seq` is higher than the one it holds, so repeated or late pages are harmless, and moves its cursor forward only.

Requirements this puts on T-131:
- **Safe horizon.** `set_change_seq()` takes the sequence value when the row is written, not when the transaction commits. A transaction that commits late appears *below* a cursor that has already moved past it, and is then missed for good. The feed must not return rows above the lowest value that could still be uncommitted (for example via the snapshot's `xmin`), or the device must re-read an overlap window. The simulation shows the loss when this is missing.
- **Deletes.** A hard delete is invisible to this feed. Synced tables need a soft delete or a tombstone row.
- **Rows never touched since V2603** have `change_seq` NULL and read as 0: the first sync is a full read of the scope.
- **Scopes and cursors.** One cursor per scope. `patient_registry_summary` has its own sequence. Price list and tax rules have no `change_seq`: they are versioned as a whole and reloaded when the version changes.
- **Scope is decided on the server** from the lease: tenant by RLS, facility by the staff member's branch, and only today's appointments and queue. The device filters nothing itself (S20 T20-BUG-1).
- The sequence is global, so its values hint at platform-wide write volume. Acceptable; noted for the security review.

## 7. Conflict rules
The principle: **what happened at the desk happened.** A patient who was checked in is in the building; a bill that was printed is in someone's hand; money that was taken is in the drawer. The server records all of it and flags what does not fit. It does not reject it and it does not quietly repair it.

Each conflict is a stored row (kind, the `opId` that caused it, device, severity, state, who resolved it and how). It is returned in the command's answer, listed by `GET /v1/sync/conflicts` so a lost answer does not lose it, and shown in the desk exception list (new kinds on the existing `/v1/front-desk/exceptions`) until a person resolves it. The device shows a count on the sync pill.

| # | Situation | What the server does | What the person does |
|---|---|---|---|
| C1 | Same patient checked in twice for the same doctor and day (two devices, or offline + online) | First accepted wins. The later one is kept as a merged entry pointing at the winner, with its provisional token, so the slip and anything billed against it still resolve | Confirms the merge; tells the patient which token is called |
| C2 | Appointment was cancelled or moved while the desk was offline | Patient is present: check-in is accepted as a walk-in and linked to the old appointment | Reviews; rebooks if needed |
| C3 | Session capacity exceeded | Accepted over capacity and flagged; a present patient is never refused by a sync | Informs the doctor |
| C4 | Same visit billed on two devices, or offline and online | Both documents are recorded (both numbers were issued). The first accepted is the bill that counts; the later is flagged `duplicate_of` and is never a second charge | Cancels the flagged bill with a credit note (existing flow, online) |
| C5 | Payment against a flagged, voided or already paid bill | Payment is recorded as held (unallocated) on the patient account | Refunds it or allocates it |
| C6 | Stock sold offline that the server no longer has; batch expired, quarantined or recalled meanwhile | The movement is recorded (stock may go below zero) and flagged. A recalled or expired batch is high severity | Pharmacist counts stock; for a recall, contacts the patient |
| C7 | Price or tax rule changed during the outage | The bill stands as printed, with the price version of the lease | Owner sees it in a report; no silent re-pricing |
| C8 | Bill total differs from the server's own calculation | Recorded as issued, flagged high severity | Billing lead corrects with a credit note and a new bill |
| C9 | Provisional patient looks like an existing patient | Created and marked as a possible duplicate (existing registry flag) | Merges or keeps (existing merge flow) |
| C10 | Staff member's permission was removed or the account disabled during the outage | Commands inside the lease window are accepted and flagged to the owner; after the lease they are refused and parked | Owner reviews |
| C11 | A number block was not returned (device lost, wiped or never synced) | Raised at day close (section 8.3) | Owner reconciles from paper copies |

Not conflicts: lease expired or block used up (the device stops and says why); tenant suspended (`423`, kept and retried); dependency not applied yet (retried).

## 8. Offline invoice numbering
### 8.1 Device series and leased blocks
Reserving a block out of the facility's main series does not work: numbers left unused when the device comes back are holes, and offline numbers interleave out of date order with online ones.

Instead each offline-capable device gets **its own series** per kind (invoice, receipt) and financial year, identified by the device code. The device is the only issuer in that series, so it can count 1, 2, 3 by itself with no gap. The server leases the series to the device in **blocks**: a ceiling the device may count up to. On sync the server records each number and, on renewal, raises the ceiling by one block past what the device reports. Nothing is ever "unused": the device takes a number only when it finalises a bill, in the same local transaction as the outbox entry.

The block bounds the damage of a lost or stolen device and of a lost local store. Suggested default: 100 bills per block (the simulation uses 8 so the limit is hit often).

Changes needed: `invoice_series` is unique on `(tenant_id, kind, fiscal_year)` today and needs a series code; `InvoiceSeriesService` gets block lease and record operations beside `allocate`. A block never crosses a financial-year end: the lease ends first.

Format: the default online number `INV-25-26-000001` is already 16 characters, the limit `InvoiceSeriesService` enforces for India. A device series needs a shorter pattern, for example `INA-25-26-00001` (15 characters, 99,999 bills per device and year). *Decision D-2 for the Money & Pharmacy lead.*

### 8.2 India (GST) — NEEDS EXPERT CHECK
- Reading of CGST Rule 46(b): the invoice number is a consecutive serial number of at most 16 characters, unique for a financial year, and may run **in one or multiple series**. On that reading a separate consecutive series per device is allowed, which is what makes this design possible. To be confirmed.
- A bill issued offline is a real tax invoice at the time of printing, dated with the corrected device time. To be confirmed, including whether the date of a bill synced after midnight needs any note.
- Numbers of bills cancelled later (C4, C8) and of a block that was never returned (C11) are presumably reported as cancelled documents in the GSTR-1 documents-issued table. To be confirmed, with the wording the accountant wants on the reconciliation record.
- Most clinic services are GST-exempt and the bill is then a bill of supply; pharmacy sales are taxable. Whether a bill of supply may share the device series or needs its own is to be confirmed.
- E-invoicing (IRN) for B2B above the turnover threshold cannot be done offline. Offline billing is B2C only.

### 8.3 Reconciliation
Day close (existing owner flow) gains one check: every block leased that day is closed, meaning the device has synced and the server holds every number from the block's start to the device's reported last number, with no hole. An open block is conflict C11. The owner resolves it by re-entering the bills from the paper duplicates (each offline bill prints its number and total; the device also offers a local export of unsynced bills for this case) or by recording the numbers as cancelled with a reason. This is the one place where the "no lost payment" promise depends on people, and it must be in the runbook and the training.

### 8.4 KSA (ZATCA) — NEEDS EXPERT CHECK
ZATCA e-invoicing is not built yet (T-291, a later phase), so this part sets constraints rather than a design.
- Reading of the Phase 2 rules: a standard (B2B) invoice must be cleared by ZATCA before it is given to the buyer, so it **cannot be issued offline**. A simplified (B2C) invoice is issued on the spot and reported within 24 hours. Each invoicing unit keeps its own invoice counter and a hash chain over its invoices, and stamps each invoice with its own key; the printed QR carries that stamp.
- The counter and hash chain are separate from the human-readable invoice number, so the device series of 8.1 can supply the number in both models below.
- **Model A, server stamps (recommended for a first release):** the device issues the bill offline as a provisional receipt with its device-series number. At sync the server builds the invoice, assigns counter and hash in its own order, stamps and reports it, and sends the compliant invoice to the patient. The lease must be shorter than 24 hours and the sync alarm earlier. Open question for the adviser: is a provisional receipt at the counter followed by the stamped invoice within hours acceptable for B2C?
- **Model B, device is its own invoicing unit:** each device holds its own key, counter and chain and prints a compliant QR offline. Heavy: onboarding per device, and the stamp uses a curve browsers' WebCrypto does not offer, so the key would live in script memory with a new library. Not recommended unless Model A is refused.
- Recommendation: T-211 ships India first. KSA offline billing waits for T-291 and the adviser's answer. KSA offline **check-in** has no tax side and can ship with India.

## 9. Security of the local store
| Rule | How |
|---|---|
| Encrypted at rest with a session-derived key (NFR) | Every record (local copy and outbox) is AES-GCM ciphertext, as `draftStore.ts` does today. The data key is derived with HKDF from a random seed the server issues with the lease, bound to session and device. It is imported non-extractable and never written to `sessionStorage` (this also retires the FEF-035 shortcut for these stores) |
| Survives a reload while offline | The data key is kept wrapped by a key derived from the staff PIN (PBKDF2) and unwrapped on unlock. Without this a reload offline would make the outbox unreadable, which is a lost payment |
| No PHI left after sign-out (NFR) | Sign-out deletes the databases, the wrapped key, the cached pages and the lease, following `clearDrafts()` |
| Sign-out with unsent work | Not allowed offline. The app says "N items not sent yet" and offers **Lock** instead: the key is dropped from memory and the PIN unlocks it. Another staff member cannot use that device until it has synced. *Decision D-3:* if clinics need shift handover during an outage, add "seal for the server" (re-encrypt the outbox to a tenant public key with ECDH, then destroy the local key, leaving ciphertext the device itself cannot read) |
| Least data on the device | Today's appointments and queue for the staff member's facility; a search index of name, record number, age/sex and last 4 digits of the phone; price list; for the pharmacy, items and batches. No notes, diagnoses, prescriptions, allergies, documents or national IDs |
| Same checks as the server | The local copy is scoped by the server. Offline screens gate on the lease's permission snapshot; the server checks again at replay |
| Bounded exposure | Lease of at most 12 hours and one block of numbers. Revoking a device or a staff member takes effect at the next contact, and at the latest when the lease ends |
| Storage that the browser may evict | Offline mode is offered only in the installed PWA with `navigator.storage.persist()` granted; otherwise the desk stays online-only. Safari's 7-day eviction of script-written storage is a reason not to support offline billing in a plain browser tab |
| Printed slip | Carries no more than the online slip |

Honest limit: a browser cannot protect data from someone who has the unlocked operating-system account and time. A PIN is short, so a stolen disk image can be attacked offline. The mitigations are the small data set, the short lease, the device policy (disk encryption, screen lock) and the block limit. This needs the security-privacy review before build (story 13).

## 10. Prototype and simulation
Code: `frontend/packages/domain/test/offline-sync/prototype.ts` (model) and `simulation.test.ts` (checks). Test-only; nothing in the product imports it. Run: `pnpm --dir frontend/packages/domain exec vitest run test/offline-sync/simulation.test.ts`. It is deterministic: all randomness comes from a seed.

The model has three devices (two desks, one pharmacy counter), a server and a network. The server copies the real PLT-026 behaviour (key per staff member, only 2xx stored, non-2xx frees the key, in-progress answers, rows that expire) and adds the guards of section 6.3. The devices keep only durable state between reconnects, so every reconnect is also a restart after a crash. The IndexedDB store itself is modelled as plain objects, not exercised.

One run is 1,000 rounds. In each round every device works offline (check-ins, bills with one or two payments, counter sales), online staff check in and bill some of the same patients and sell the same stock, idempotency rows expire, and one device gets a bad connection: requests are lost (8%), delayed and delivered later out of order (8%), duplicated in transit (8%), answered but the answer lost (10%); the server fails before commit (3%) or commits and loses its idempotency bookkeeping (3%); the link drops after 1 to 30 requests; half of the reconnects send four commands at once so they overtake each other; one reconnect in ten is by a different staff member; delta pages are lost (15%) or an old page arrives after a newer one. At the end the link is good until every outbox is empty.

Checks after the final sync, against the harness's own record of what happened at the desks: one invoice per action, per number and per visit that counts; every bill present with its number and amount; every series consecutive from 1 and equal to the device's counter; every payment present once with its amount, held exactly when its bill is flagged, and total money equal; every check-in present once with its provisional token; one visit that counts per patient and doctor; real tokens 1..n per doctor; every conflict stored and delivered to the device that caused it; no outbox entry stuck or parked; every device's copy equal to the server.

**Result, seed 31, 1,000 reconnects: 0 duplicate bills, 0 lost payments, 0 conflicts not surfaced.** Ten more seeds are clean too.

| What happened in the seed 31 run | Count |
|---|---|
| Offline check-ins / bills / payments | 1,261 / 2,753 / 3,596 |
| Requests sent; timeouts seen by a device | 11,600; 2,903 |
| Late or duplicated requests delivered out of order | 1,744 |
| Answers replayed from the idempotency store | 2,348 |
| Re-runs caught **only** by the durable `client_op_id` | 164 |
| "In progress" answers; "dependency not applied" answers | 346; 686 |
| Conflicts raised: duplicate check-in / duplicate bill / payment held / oversold | 190 / 800 / 1,059 / 21 |
| Bills a device refused to issue because its block was used up | 369 |

The conflict rates are far above real life on purpose: the desks are made to pick the same patients.

Each guard was then switched off to prove the checks can fail:

| Guard removed | Outcome of the same run |
|---|---|
| Durable `client_op_id` (PLT-026 only) | Duplicate bills and payments (279 violations) |
| Keep until a definite answer (device forgets on send) | Lost bills and lost payments (1,929 violations) |
| Conflicts stored as rows (only in the HTTP answer) | 35 conflicts never reach the desk |
| Safe horizon on the delta feed | Two devices end with a copy that differs from the server |

What the spike found:
1. **PLT-026 alone does not prevent duplicate bills offline.** Three separate holes: the 24-hour lifetime, the key being per staff member, and the 5-minute takeover after a crash between commit and bookkeeping. The durable `client_op_id` closes all three and is the first Phase 2 story.
2. A conflict must be a stored row; an answer can be lost.
3. The `change_seq` feed needs a safe horizon before any client depends on it.
4. The block limit is a real stop for the cashier. Block size, renewal while the link is still healthy and a clear "sync to continue" message are product work, not details.

Not simulated: loss of the device's local store (covered by 8.3, a people process), clock errors, tax arithmetic, the encryption, and real browsers. Story 12 moves the harness onto the real API.

## 11. Phase 2 stories for T-211
Estimates are engineer-days for one engineer, including tests. Owner areas: PLT = Platform, ACC = Patient Access, FIN = Money & Pharmacy.

| # | Story | Owner | Days | Needs |
|---|---|---|---|---|
| 1 | Durable `client_op_id`: unique column on queue entries, invoices, payments and stock movements; the four replay endpoints accept the offline envelope, answer a re-run with the existing row, and return `DEPENDENCY_NOT_APPLIED` | PLT | 5 | PLT-026 |
| 2 | Delta feed on `change_seq` with safe horizon, tombstones, per-scope cursors and facility scope (completes T-131) | PLT | 6 | T-131 |
| 3 | Device registration and offline lease: device code, signed lease, renew, revoke, permission snapshot, key seed | PLT | 5 | |
| 4 | Encrypted local store in staff-web: local copy and outbox in IndexedDB, key derivation, PIN lock and unlock, wipe on sign-out, persistence check, installed-PWA gate | PLT | 6 | 3 |
| 5 | Outbox replay engine from the prototype, service-worker caching of the desk and counter routes, sync pill with counts (NB-328, SC-40) | PLT | 6 | 1, 4 |
| 6 | Provisional tokens: issue offline, display token on the entry, slip, board, voice and WhatsApp text, call order by arrival time, merged entries (C1 to C3) | ACC | 6 | 1, 5 |
| 7 | Offline quick registration as a provisional patient, duplicate check at sync (C9) | ACC | 3 | 5 |
| 8 | Device invoice and receipt series with leased blocks; day-close reconciliation and the unreturned-block flow (C11); India | FIN | 7 | 3, GST opinion |
| 9 | Offline desk bill and payment: local totals from the shared money code, receipt print, flagged duplicate bill and held payment (C4, C5, C7, C8) | FIN | 7 | 5, 8 |
| 10 | Offline pharmacy counter sale, non-prescription only: local batch pick, stock movement replay, oversold and recalled-batch flags (C6) | FIN | 6 | 5, 8, PHL-019 |
| 11 | Conflict store, `/v1/sync/conflicts`, new desk-exception kinds and the resolve actions (merge, cancel with credit note, refund or allocate) | PLT + FIN | 6 | 1 |
| 12 | Replay and chaos suite on the real API (this harness against Testcontainers, plus browser offline tests), nightly | PLT | 5 | 5 to 11 |
| 13 | Security-privacy review, threat model for the local store, clinical-safety review of the pharmacy scope, penetration test of a stolen device | PLT | 4 | 4 |
| 14 | Downtime runbook, reconciliation guide, training module update, pilot in two clinics with measured outage drills | PLT | 4 | all |
| 15 | KSA: ZATCA offline model (8.4) once T-291 exists and the adviser has answered | FIN | 6 | T-291, ZATCA opinion |

Total: 82 days (76 without KSA). Order: 1 and 2 first (useful online too), then 3 to 5, then 6 to 11 in parallel, then 12 to 14. Stories 8 and 15 must not start before the expert answers.

## 12. Decisions and review
| # | Decision | Recommendation | Owner |
|---|---|---|---|
| D-1 | Patient-facing token after sync | Keep the provisional token for the visit | Patient Access lead |
| D-2 | Device series format and block size | `IN{device}-{FY}-` + 5 digits; 100 per block | Money & Pharmacy lead |
| D-3 | Sign-out with unsent work | Lock only; no handover seal in the first release | Platform lead + security |
| D-4 | KSA model | Model A after T-291; India first | Money & Pharmacy lead + ZATCA adviser |
| D-5 | Longest offline period | Lease of 12 hours or end of clinic day | Platform lead |
| D-6 | GST reading in 8.2 | Get a written opinion before story 8 | Money & Pharmacy lead + chartered accountant |

Review required by the story. **Not done yet**; this table is filled in by the leads.

| Reviewer | Date | Outcome |
|---|---|---|
| Platform lead | | |
| Patient Access lead | | |
| Money & Pharmacy lead | | |
