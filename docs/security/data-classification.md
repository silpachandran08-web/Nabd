# Data classification standard (PLT-052)

Status: **Draft, pending DPO approval** (Needs external action: the DPO signs the approval table at the end).
Owner: security-privacy. Tracker: T-315 (standard and tags), T-077 (threat models). Sources: NB-255, SC-54, ADR-04, DPO-04.

Every column in the `public`, `identity` and `consent` schemas, and every structured log field, has exactly one tier
in [`db/classification.yaml`](../../db/classification.yaml). Code and infrastructure apply the handling rules below
by tier. When in doubt, pick the stricter tier and flag it for DPO review.

## Tiers

| Tier | Meaning | Examples (column) |
|---|---|---|
| `phi` | Health information about an identifiable patient, or anything that reveals that a person received a specific kind of care. Includes free text written in a clinical context, because it can carry anything. | `clinical_notes.diagnosis`, `vitals.*`, `patient_allergies.substance`, `prescription_items.drug_name`, `queue_entries.presenting_complaint`, `invoice_line_items.charge_name` (a line on a patient's bill names the service), `audit_log.before/after` |
| `pii` | Identifies or contacts a person (patient, guardian, staff, owner) without health content; also credential material tied to a person (hashes, encrypted secrets, OTP codes). | `patients.name/phone/dob/national_id_enc`, `staff.email`, `sessions.ip_address`, `staff.pin_hash`, any `patient_id` reference |
| `financial` | Money, tax and payment data of the clinic or of a payer. | `invoices.total`, `invoice_payments.reference`, `tax_registrations.gstin_or_vat_no`, `day_closes.counted_cash` |
| `operational` | Everything else: surrogate ids, tenant ids, staff/actor ids, status codes, timestamps, configuration, reference data. | `queue_entries.status`, `tenants.timezone`, `safety_dictionary_versions.version` |

Rules that decide the tier:
1. **Strictest wins.** A value built from several columns (a DTO, a cache entry, an export row, an event) takes the
   strictest tier of its sources: `phi` > `pii` > `financial` > `operational`.
2. **Untagged means PHI.** Code that cannot find a column's tag treats it as `phi` (default-deny, ADR-04).
3. **Surrogate ids are operational, person references are PII.** A row `id` or `staff_id` is opaque; a `patient_id`
   (and `guardian_id`, `merged_into_id`, ...) links data to a patient and is `pii`.
4. **Free text takes the tier of its context.** Reason/note fields on clinical or patient rows are `phi`; on staff rows
   `pii`; on money rows `financial`.
5. **Partitions inherit** their parent's tags (`phi_read_log_y2026m10` = `phi_read_log`).

## Handling rules

| Where | `phi` | `pii` | `financial` | `operational` |
|---|---|---|---|---|
| **Storage** | Tenant table with RLS enabled and forced; national ids and secrets field-encrypted (FieldCipher, PLT-044); in-region only | Same as PHI; credential material only as hash or encrypted (`*_hash`, `*_enc`) | Tenant table with RLS; exact decimals | No restriction beyond tenancy |
| **Logs** (app logs, stack traces, access logs) | Never. Log ids only. PhiMasker is a safety net, not a control (PLT-037) | Never in clear (no names, phones, emails, OTPs, tokens); hashed ids allowed | Amounts allowed only in audit events, not app logs | Allowed |
| **Caches** (Caffeine, Valkey, HTTP) | Default deny (ADR-04). Only with a tenant-scoped key **and** an explicit allow in code, with a TTL; through `CacheKeyPolicy` | Tenant-scoped key required | Tenant-scoped key required | Global key allowed |
| **Cache keys** | Never contain a PHI/PII value: opaque ids only (`CacheKeyPolicy` refuses `:` and whitespace in parts) | same | same | same |
| **Analytics / reporting / rollups** | Aggregates only, minimum cell size per the reporting design; no row-level PHI leaves the tenant | Pseudonymised (hash or surrogate id); no names, phones | Allowed inside the tenant; cross-tenant only aggregated without tenant names | Allowed |
| **Exports** (CSV/Excel/PDF, data export jobs) | Only to an authorised tenant user, audited (PHI read log), delivered by expiring secure link, never e-mail attachment | Same as PHI | Audited; accountant exports carry no patient names (ADM-050) | Allowed |
| **Messaging** (WhatsApp, SMS, e-mail, push) | Never in the message body or template variables (lock-screen safe, COM-014); deliver through an OTP-protected secure link (IDC-015) | Minimum needed to address the patient (first name, clinic name); phone numbers only to the provider API | No amount breakdown in the body; totals only via the secure link | Allowed |
| **Events / outbox / streams** | Never in payloads (OPS-015: data is PHI-free, ids only); consumers fetch by id | Never; ids only | Ids only | Allowed |
| **URLs and query strings** | Never (non-negotiable 3) | Never | Never | Allowed |
| **Third parties** (providers, LLMs, support tools) | Only under a DPA, in-region, minimum fields; never to support tickets (PLT-057) | Same | Payment providers only | Allowed |
| **Test data / fixtures / docs** | Synthetic only | Synthetic only | Synthetic only | Allowed |

## Tagging columns

- Every migration that adds or renames a column in `public`, `identity` or `consent` adds the tag to
  `db/classification.yaml` **in the same change**. The file is reviewed like schema (CODEOWNERS: db and security owners).
- CI (`.github/workflows/migrations.yml`):
  - `python3 scripts/ci/classification_check.py --base <base>` fails when an added migration creates an untagged column;
  - after the migrate job, `classification_check.py --db <url>` compares the migrated schema with the file and fails on
    untagged columns and on stale tags (a dropped column must lose its tag).
- Self-test: `python3 scripts/ci/test_classification_check.py` (failing and passing sample migrations under
  `scripts/ci/fixtures/classification/`).
- Lines marked `# uncertain:` are judgement calls waiting for DPO review; resolve them by removing the comment or
  changing the tier.

## Log fields

The structured log line (`PhiJsonEncoder`) has the fields listed under `log_fields` in the classification file, all
`operational`. `message` and `stack_trace` are free text: they must not carry PHI or PII at all. A new log field
(MDC key, JSON field) is added to `log_fields` with its tier; only `operational` fields may be logged.

## Threat models

PHI, auth and messaging epics are threat-modelled (STRIDE + LINDDUN) before their first build sprint:
[template](threat-models/TEMPLATE.md), [EP07 WhatsApp](threat-models/EP07-whatsapp.md),
[EP08 consent](threat-models/EP08-consent.md), [EP12 Patient Access Edge](threat-models/EP12-patient-access-edge.md),
[EP19 uploads and secure links](threat-models/EP19-uploads-secure-links.md),
[EP20 tax and billing](threat-models/EP20-tax-billing.md).

## Approval

| Role | Name | Decision | Date |
|---|---|---|---|
| DPO | _pending (Needs external action)_ | | |
| Security lead | _pending_ | | |
