# Threat model: EP12 Patient Access Edge (WhatsApp booking bot)

Status: Draft, recorded before the first EP12 build sprint (S5) (T-077); ACC-039 requires every threat here to map to a
control and an automated test. Needs security-privacy review and DPO approval of the LINDDUN part.
Scope: patient-edge service (conversation store, availability projection), Core internal booking API.
Stories ACC-038, ACC-039, ACC-040, ACC-042, ACC-043, ACC-045, COM-016. Tasks T-120, T-162, T-165, T-166, T-167, T-189.

## Assets and data
| Asset | Tier | Where it lives |
|---|---|---|
| Conversation state (step, chosen slot, patientRef) | pii | Edge DB / Valkey |
| Household lookup result (patientRef, first name, relationship, age band) | pii | Core internal API response, Edge memory |
| Registration data typed in chat (name, DOB, sex) | pii | Core `patients` (after consent) |
| Availability projection (doctor-day slots) | operational | Edge / Valkey |
| Edge client certificate and internal audience tokens | secret (credential) | secret store |

## Trust boundaries and flows
1. Patient -> Meta -> Comms -> Edge (inbound message event, deduped by messageId).
2. Edge -> Core `/internal/v1/tenants/{t}/...` over mTLS with internal audience; Idempotency-Key on every POST.
3. Core outbox -> Edge projection invalidation.
4. Edge -> Comms -> patient replies.

## STRIDE
| # | Category | Threat | Mitigation | Task |
|---|---|---|---|---|
| S1 | Spoofing | Anything but the Edge calls the internal booking API | Client certificate plus internal `aud`; 401 otherwise (ACC-038); separate token types | T-120, T-001 |
| S2 | Spoofing | Someone with another person's phone books as them | Household lookup only for a verified phone; minimum fields returned | T-120, T-162 |
| T1 | Tampering | Replayed or retried booking creates duplicates | Idempotency-Key required; replay returns identical result; status check before replying on timeout | T-120, T-031, T-162 |
| T2 | Tampering | Two chats race for one slot | Soft hold in Edge, Core exclusion constraint is the arbiter (409 SLOT_TAKEN) | T-120 |
| R1 | Repudiation | Patient disputes a booking | Audit row names actor "patient via edge"; message ids kept | T-120 |
| I1 | Information disclosure | Edge identity allowed for tenant A reads tenant B | Per-tenant allow list on Edge identity (403); cross-tenant replay fuzzer | T-120, T-100 |
| I2 | Information disclosure | Bot reply mentions another clinic's doctor, slot or patient | Output guard blocks replies with foreign tenant ids; property test over generated conversations | T-167 |
| I3 | Information disclosure | Edge logs carry phones, names or message bodies | Automated log scan in ACC-039; PHI log rules | T-120, T-006 |
| I4 | Information disclosure | Adult sibling sharing a phone sees a dependant's bookings | Dependants listed only with proxy consent; age-18 removal; revocation within 1 min | T-165, T-297 |
| D1 | Denial of service | Message flood from one phone exhausts Core | 20/min per phone limit answered once, no Core call | T-120, T-074 |
| D2 | Denial of service | Valkey or projection loss stalls bookings | Single Core refill per doctor-day; Core outage message and clean resume (game day) | T-120, T-189 |
| E1 | Elevation of privilege | Edge DB role reaches Core tables | Edge role has no grants on Core tables; ArchUnit forbids compile dependency | T-120 |

## LINDDUN (privacy)
| Category | Threat | Mitigation | Task |
|---|---|---|---|
| Linkability | Shared sender links a phone's bookings across clinics | Tenant-scoped conversations; branch asked once; channel exclusivity | T-162, T-167 |
| Identifiability | Household list shows full names and DOB | First name, relationship and age band only | T-120, T-165 |
| Non-repudiation (privacy) | Conversation history kept indefinitely | Resume window 24 h; retention per purge policy | T-162, T-083 |
| Detectability | Funnel dashboards reveal individual patients | Funnel metrics without PHI | T-189 |
| Disclosure of information | New patient gives health data before notice | Notice and consent timestamped before any health data is asked | T-166, T-137 |
| Unawareness | Patient does not know a human may read the thread | Hand-off to reception is announced to the patient | T-162 |
| Non-compliance | Minor books without guardian consent | Under-18 DOB requires guardian consent before booking | T-166 |

## Open items
- Emergency phrase list for the bot (ACC-040) needs clinical sign-off: Needs clinical sign-off; not part of this model.
- mTLS certificate issuance and rotation run in the target cluster: Needs external action.
