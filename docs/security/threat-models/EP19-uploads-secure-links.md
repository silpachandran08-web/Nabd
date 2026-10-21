# Threat model: EP19 Uploads, documents and OTP secure links

Status: Draft, recorded before the first EP19 build sprint (S6) (T-077). Needs security-privacy review and DPO approval of the LINDDUN part.
Scope: secure upload pipeline (quarantine, type check, AV scan, regional tenant storage, signed URLs), OTP-protected
secure links for prescriptions, invoices, reports and discharge summaries, link-open logging, signed consent PDFs.
Stories PLT-029, PLT-030, PLT-041, IDC-015, COM-050, PHL-049, HOS-019, CLN-014.
Tasks T-143, T-179, T-180, T-220, T-303, T-331, T-374.

## Assets and data
| Asset | Tier | Where it lives |
|---|---|---|
| Uploaded files (outside reports, photos, consent PDFs) | phi | regional object storage under `tenant/{tenantId}/` |
| Generated documents (Rx, report, invoice, discharge summary PDFs) | phi / financial | object storage, hash in DB |
| Secure link tokens and OTPs | secret (credential) | DB (hashed), WhatsApp message (link only) |
| Link-open log rows (outcome, link id, device family, IP) | pii | `phi_read_log` / secure link opens |

## Trust boundaries and flows
1. Staff browser -> Core upload init -> direct upload to quarantine bucket -> scan -> available.
2. Doctor -> Core -> short-lived signed URL (<= 5 min) -> storage.
3. Core -> Comms -> patient WhatsApp: utility message with link only.
4. Patient browser -> secure link page -> OTP -> PDF.

## STRIDE
| # | Category | Threat | Mitigation | Task |
|---|---|---|---|---|
| S1 | Spoofing | Someone who got the link (forwarded chat, shared phone) opens the document | OTP required; 5 wrong OTPs lock for 30 min; expiry 1-30 days | T-179 |
| S2 | Spoofing | Proxy with booking-only scope receives clinical links | No clinical secure link for BOOK_ONLY proxies; proxy scope model | T-179, T-297 |
| T1 | Tampering | Executable disguised as PDF, or malware upload | Magic-byte type check, AV scan, file never leaves quarantine on failure, size limit (413) | T-143 |
| T2 | Tampering | Signed document altered after issue | SHA-256 stored at issue and returned on every download; later change is an addendum | T-303, T-374, T-143 |
| R1 | Repudiation | Dispute over whether a patient opened a report | Every open attempt logged with outcome (otp_ok, otp_failed, expired), visible to the owner | T-180 |
| I1 | Information disclosure | Signed URL reused after the consult | URL valid <= 5 min; storage returns 403 after expiry; phi_read_log row per issue | T-143, T-048 |
| I2 | Information disclosure | Cross-tenant or public object access | Objects under tenant prefix in the region bucket, no public bucket | T-143 |
| I3 | Information disclosure | GPS EXIF in photos reveals home location | Metadata stripped before storage | T-143 |
| I4 | Information disclosure | Drug, test or diagnosis text in the message or list rows | Message carries clinic, item type, date and link only; list rows linted | T-179, T-331, T-126 |
| I5 | Information disclosure | Sensitive results auto-released to the patient | No auto-release for configured sensitive tests; doctor release or timer | T-220 |
| D1 | Denial of service | Large or many uploads exhaust storage or scanner | Size limit at init; scan queue isolated from API | T-143 |
| E1 | Elevation of privilege | Withdrawn-consent patient's attachments still listed | Listing and URL issuance return 404 under the clinical read gate | T-143, T-047 |

## LINDDUN (privacy)
| Category | Threat | Mitigation | Task |
|---|---|---|---|
| Linkability | Open logs link a device across patients | Device family only, no full User-Agent, no OTP in the row | T-180 |
| Identifiability | Link URL contains patient or document identity | Opaque token in URL; nothing identifying in path or query | T-179 |
| Non-repudiation (privacy) | Open log kept longer than needed | Partitioned read log with archive policy | T-048, T-209 |
| Detectability | Lock-screen preview reveals a report exists for a sensitive test | Generic utility text, no test name | T-220, T-126 |
| Disclosure of information | Documents sent as chat attachments | Never attach files; secure link only | T-179 |
| Unawareness | Patient cannot see who opened their records | Owner audit screen; patient access history (COM-050 privacy menu) | T-239, T-331 |
| Non-compliance | Files stored outside the tenant's region | Region bucket per tenant | T-143 |

## Open items
- Which tests are never auto-released (PHL-049 names HIV) needs clinical sign-off per market: Needs clinical sign-off.
- Object storage, KMS and AV engine are provisioned outside this repo: Needs external action.
