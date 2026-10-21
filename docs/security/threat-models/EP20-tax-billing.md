# Threat model: EP20 Tax, invoicing, payments and refunds

Status: Draft, recorded before the remaining EP20 build sprints (FIN-001 shipped in S1; FIN-006 onward from S4) (T-077).
Needs security-privacy review and DPO approval of the LINDDUN part.
Scope: payments, gapless invoice series, tax engine, invoice print/PDF and share, refunds and credit notes, dues and
reminders, UPI links and webhooks, gateway refunds, pharmacy tax, revenue and tax reports, multi-branch series.
Stories FIN-001, FIN-006, FIN-008, FIN-009, FIN-012, FIN-013, FIN-014, FIN-020, FIN-021, FEF-047, PHL-011, PHL-021,
ADM-048, ADM-050, PLT-025. Tasks T-030, T-107, T-122, T-144, T-152, T-163, T-181, T-213, T-244, T-332, T-342.

## Assets and data
| Asset | Tier | Where it lives |
|---|---|---|
| Invoice headers, payments, credit notes, series counters | financial | `invoices`, `invoice_payments`, `invoice_series` |
| Invoice line names on a patient's bill | phi | `invoice_line_items.charge_name` |
| Tax registrations (GSTIN / VAT number) | financial | `tax_registrations`, `tenants.tax_id` |
| Payment provider webhook secrets and API keys | secret (credential) | secret store |
| Payment links and UPI intents | financial | Core |
| Day close cash counts | financial | `day_closes` |

## Trust boundaries and flows
1. Cashier (staff app) -> Core billing API -> invoice, payment, audit.
2. Payment provider -> webhook -> Core (idempotent by provider event id); poller as backstop.
3. Core -> Comms -> patient or caregiver: payment link or invoice secure link.
4. Owner / accountant -> reports from rollups -> export by secure link.

## STRIDE
| # | Category | Threat | Mitigation | Task |
|---|---|---|---|---|
| S1 | Spoofing | Forged payment webhook marks an invoice paid | Signature check, 401 and nothing processed on a bad signature | T-181 |
| S2 | Spoofing | Same user requests and approves a refund | Maker-checker: 403 SAME_USER; owner approval above the tenant threshold | T-152 |
| T1 | Tampering | Concurrent payments or retries corrupt paid amount | Atomic payment, status derived from sum of payments; Idempotency-Key; nightly consistency job | T-030, T-031 |
| T2 | Tampering | Invoice numbers skipped, duplicated or back-dated | Gapless series allocated in the committing transaction per GSTIN/branch/FY; credit notes dated today | T-107, T-152 |
| T3 | Tampering | Reprint shows today's price or rate, not the issued values | Stored tax breakdown and values of the original issue; duplicate-copy marking | T-122, T-144 |
| T4 | Tampering | Free-text or overridden prices bypass the catalogue | Server-side pricing from the catalogue; override needs authority and reason | T-033 |
| R1 | Repudiation | Cashier denies a refund, write-off or void | Audit events for every state change; write-off needs billing:approve + reason | T-152, T-053 |
| I1 | Information disclosure | Invoice line names reveal treatment in WhatsApp | Message carries a secure link only, no amount breakdown or diagnosis | T-144, T-179 |
| I2 | Information disclosure | Accountant export carries patient names | GST/VAT summary without patient names, by secure link | T-244 |
| I3 | Information disclosure | Branch staff see another branch's revenue | Branch-scoped access; combined view only for owners, from rollups | T-332 |
| D1 | Denial of service | Lost webhooks leave payments unrecorded | Poller marks the intent paid within 15 min, once | T-181 |
| E1 | Elevation of privilege | Discount or refund without permission | Permission checks; threshold approver role | T-152 |

## LINDDUN (privacy)
| Category | Threat | Mitigation | Task |
|---|---|---|---|
| Linkability | Payer (caregiver) linked to patient across tenants | Payer recorded per tenant only | T-181 |
| Identifiability | Revenue reports identify patients | Rollups by service, doctor and payer type; counts without names | T-244, T-332 |
| Non-repudiation (privacy) | Payment references kept beyond statutory need | Retention aligned with tax record rules (DPO decision) | gap: no tracker task |
| Detectability | Dues reminders reveal treatment to whoever holds the phone | No line items or diagnosis in reminder body; quiet hours | T-152, T-145 |
| Disclosure of information | Payment provider receives treatment details | Provider gets amount and invoice reference only | T-181 |
| Unawareness | Patient does not know a caregiver paid or was messaged | Payment records payer; message history on the patient file | T-181, T-103 |
| Non-compliance | Wrong tax per market (GST/VAT, zero-rating) | Tax engine with golden-file tests per market | T-122, T-213 |

## Open items
- Tax rules and worked examples need a chartered accountant per market (FIN-010): Needs external action.
- Retention period for financial records per market: DPO / legal decision, no tracker task yet (gap above).
- KSA e-invoicing (ZATCA, T-291) is a later phase and needs its own model update when scheduled.
