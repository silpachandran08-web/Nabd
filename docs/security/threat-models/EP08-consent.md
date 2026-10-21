# Threat model: EP08 Consent and privacy notices

Status: Draft, recorded before the first EP08 build sprint (S4) (T-077). Needs security-privacy review and DPO approval. Purposes, defaults and notice model: docs/adr/0018-consent-purposes-and-notices.md (IDC-002).
Scope: consent capture at the desk, in WhatsApp and in assisted mode; processing default-deny; withdrawal propagation;
PHI-safe patient notifications. Stories IDC-002, IDC-004, IDC-005, IDC-006, IDC-007, COM-014, ACC-006.
Tasks T-104, T-137, T-138, T-126, T-302 (consent schema later: T-249).

## Assets and data
| Asset | Tier | Where it lives |
|---|---|---|
| Consent rows (purpose, granted/withdrawn, notice version, channel, captured_by, witness) | pii (patient_id) / operational | `consents` (Core; later `consent` schema, T-249) |
| Channel evidence (wamid, OTP verification) | pii | `consents`, Comms inbound events |
| Privacy notice versions and mandatory clauses | operational | privacy notices table (T-104) |
| Patient health data gated by consent | phi | clinical tables |

## Trust boundaries and flows
1. Receptionist (staff app) -> Core consent API -> `consents` + audit.
2. Patient (WhatsApp) -> Meta -> Comms webhook -> Core consent (before any health data is stored, IDC-005).
3. Withdrawal (STOP or desk) -> outbox event `patient.consent.changed.v1` -> Comms and every read path.
4. Read paths -> consent gate (IDC-001) -> clinical data.

## STRIDE
| # | Category | Threat | Mitigation | Task |
|---|---|---|---|---|
| S1 | Spoofing | Forged channel evidence (fake wamid) claims WhatsApp consent | Evidence taken only from signature-verified, persisted inbound events | T-103, T-137 |
| S2 | Spoofing | Replayed OTP used to capture consent | OTP rate limits per phone, single use and expiry | T-052, T-137 |
| S3 | Spoofing | Staff capture consent without the patient present | Channel recorded; assisted mode needs a witness; second-witness rule rejects same staff (IDC-007) | T-138 |
| T1 | Tampering | Notice text edited after consent was captured | Versioned notices; each consent joins exactly one (tenant, version, language) row; mandatory clauses locked per market | T-104 |
| T2 | Tampering | Consent rows altered or deleted to hide a withdrawal | Append-only consent history and hash-chained audit log | T-137, T-053 |
| R1 | Repudiation | Patient or clinic disputes what was agreed | Audit `patient.consent_captured` / `patient.consent_withdrawn` with purposes and notice version, no PHI | T-137 |
| I1 | Information disclosure | Reschedule/cancel messages reveal visit reason | Utility templates without diagnosis or visit-reason text; template PHI lint | T-302, T-126 |
| I2 | Information disclosure | Data of a patient without processing consent is served | Default-deny: no processing consent -> clinical reads 404, registry shows consent needed | T-137, T-047 |
| D1 | Denial of service | Withdrawal event lost, messaging continues | Outbox delivery with retry; queued reminders re-check consent at send time; chaos test < 1 min | T-137, T-135 |
| E1 | Elevation of privilege | Guardian or proxy acts beyond scope (adult dependant) | Proxy scope, age-18 handover and revocation | T-297 |

## LINDDUN (privacy)
| Category | Threat | Mitigation | Task |
|---|---|---|---|
| Linkability | Consent evidence links WhatsApp identity to patient record across tenants | Consent rows tenant-scoped under RLS | T-137, T-011 |
| Identifiability | Notice acknowledgement stores more identity than needed | Store notice version and channel evidence id only | T-104 |
| Non-repudiation (privacy) | Withdrawal deletes clinical data that must be kept | Withdrawal stops processing and messaging, never destroys clinical records (non-negotiable 4) | T-137 |
| Detectability | Messaging blocked state visible to unrelated staff | Message history behind patient permission | T-103 |
| Disclosure of information | Marketing pre-selected | Marketing never pre-selected, stored false by default | T-137 |
| Unawareness | Elderly or low-literacy patients do not understand | Assisted mode with local-language audio and large text explainer | T-138 |
| Non-compliance | Purposes or defaults not approved per market | DPO sign-off of purposes and defaults recorded in docs/adr (IDC-002) | T-104 |

## Open items
- Mandatory notice clauses per market (IN DPDP, SA PDPL, AE, QA) need legal text from the DPO: Needs external action; not drafted here.
