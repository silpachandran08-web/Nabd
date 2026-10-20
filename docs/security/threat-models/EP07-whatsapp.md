# Threat model: EP07 WhatsApp messaging (Comms Gateway)

Status: Draft, recorded before the first EP07 build sprint (S4) (T-077). Needs security-privacy review and DPO approval of the LINDDUN part.
Scope: Comms Gateway service and Core messaging module. Stories COM-001, COM-002, COM-004, COM-006, COM-007, COM-011,
COM-012, COM-017, COM-023, OPS-045. Tasks T-103, T-110, T-119, T-135, T-145, T-217.

## Assets and data
| Asset | Tier | Where it lives |
|---|---|---|
| Inbound webhook raw bodies (patient messages, phone numbers) | phi | Comms DB (`whatsapp_inbound_events`, encrypted body, 30-day purge) |
| `whatsapp_messages.to_phone`, `params` | pii / phi | Core / Comms DB |
| Template catalogue (`whatsapp_templates`) | operational | Core / Comms DB |
| WABA access tokens, webhook app secret | secret (credential) | secret store reference only (COM-017) |
| Messaging consent state | pii | `consents` |
| Notification requests and outbox events | operational (ids only) | Core outbox, Valkey streams |

## Trust boundaries and flows
1. Meta Cloud API -> public webhook endpoint (Comms) -> persist-then-ack -> processor.
2. Core module -> NotificationPort -> outbox -> Comms consumer -> Meta send API.
3. Comms -> Core render-params call (PHI-minimised) and consent check.
4. Owner -> embedded signup -> token exchange -> secret store.

## STRIDE
| # | Category | Threat | Mitigation | Task |
|---|---|---|---|---|
| S1 | Spoofing | Forged webhook (no or wrong `X-Hub-Signature-256`) injects fake inbound messages or STOP/START | Verify signature before persisting; 401, nothing stored, security metric (COM-001) | T-103 |
| S2 | Spoofing | Forged button payload token routes a reply to another clinic | Signed payload token resolved to its tenant; forged token rejected (COM-011) | T-110 |
| S3 | Spoofing | Attacker re-uses a clinic's WABA credentials | Token kept only as a secret-store reference, never logged or returned; installation vs tenant secrets separated | T-217, T-314 |
| T1 | Tampering | Duplicate or reordered status callbacks rewind message status | Dedupe by wamid; status only moves forward (COM-001, OPS-045) | T-103, T-135 |
| T2 | Tampering | Template edited to carry PHI after approval | Template lint on every submit; platform override audited (COM-014) | T-126 |
| R1 | Repudiation | Patient disputes having sent STOP, or clinic disputes a send | Raw body persisted before ack with wamid; consent withdrawal row and audit event; send outcome per message | T-103, T-137 |
| I1 | Information disclosure | Diagnosis, drug or result text on a lock screen | Templates and variables PHI-free, typed allow-listed slots; secure link for content | T-126, T-145, T-179 |
| I2 | Information disclosure | Reply from a patient lands in another tenant's inbox (shared number) | Routing by token tenant; ask-which-clinic when ambiguous; channel exclusivity guard | T-110, T-167 |
| I3 | Information disclosure | Phone numbers or bodies in logs | Log only ids; PHI log masking and scan | T-006 |
| I4 | Information disclosure | Comms service reads Core tables directly | Own DB and role, CI test that the role has no SELECT on Core tables (COM-007) | T-119 |
| D1 | Denial of service | Webhook storm or Meta outage blocks Core or loses messages | Persist-then-ack p95 < 100 ms; Comms isolated from Core; chaos suite; send windows expire late sends | T-103, T-119, T-135, T-145 |
| D2 | Denial of service | 429 storms cause duplicate sends | Per phone_number_id token bucket; idempotent dispatch | T-135, T-074 |
| E1 | Elevation of privilege | A module bypasses consent by calling the WhatsApp service directly | Single NotificationPort; ArchUnit rule; blocked status when no consent (COM-004) | T-119, T-145 |

## LINDDUN (privacy)
| Category | Threat | Mitigation | Task |
|---|---|---|---|
| Linkability | One phone shared by a family links several patients | STOP withdraws for every patient on the phone; first name only in messages | T-103, T-165 |
| Identifiability | Message metadata at Meta identifies the patient and the clinic type | Clinic display name only, utility templates, no specialty or diagnosis text | T-110, T-126 |
| Non-repudiation (privacy) | Raw bodies kept longer than needed | 30-day purge of encrypted raw body, dedupe key kept | T-103, T-083 |
| Detectability | Read receipts or failure reasons reveal a patient relationship to staff without need | Message history behind patient:view permission | T-103 |
| Disclosure of information | Template variables leak through Meta | PHI lint and slot allow-list | T-126 |
| Unawareness | Patient does not know messaging consent can be withdrawn | STOP/START keywords in 5 launch languages, confirmation message | T-103, T-137 |
| Non-compliance | Marketing sent without consent | Separate marketing consent, default deny, refusal recorded | T-137 |

## Open items
- Meta-side data retention and processing location for message content: DPO decision (Needs external action).
- Webhook-secret rotation drill is a runbook/game-day item (COM-007), not automatable here.
