# ADR 0018: Consent purposes and versioned privacy notices

- **Status: PROPOSED. DPO sign-off pending (Needs external action).** The purposes and defaults below take effect as a decision only when the DPO signs in section 9.
- Story: IDC-002 (T-104). Builds on EP08 threat model `docs/security/threat-models/EP08-consent.md`. Capture is built in IDC-004, WhatsApp STOP in COM-002.
- Migration: `db/migrations/V1830__consents_notice_version.sql`. Interface: `patient.api.ConsentRegistry`.

## 1. Problem

Today the `consents` table (V5, V28, V104) has no notice reference, and "no row" means *allowed* for `data_processing`
(`PatientRepository.CONSENT_GATE`) but *blocked* for messaging (`WhatsAppMessageRepository.hasMessagingConsent`). A consent
cannot be traced to the text the patient saw. This ADR fixes the model before capture is built.

## 2. Purposes and default states

A purpose is a **row value** (`consents.consent_type`), not a column, so a new purpose is a CHECK change, never a table change.

| Purpose (`consent_type`) | Default | Meaning | Gate in code |
|---|---|---|---|
| `data_processing` | **deny** | Clinic may process this patient's data | `CONSENT_GATE` (withdrawn row hides the patient); capture-before-registration in IDC-004/005 |
| `messaging` | **deny** | Transactional WhatsApp/SMS messages | `WhatsAppMessageRepository.hasMessagingConsent` (send gate; also re-checked at send time) |
| `marketing` | **deny**, never pre-selected | Promotional messages | Marketing campaign gate (future story); must not fall back to `messaging` |
| `guardian_access` | **deny** | A guardian may act for a dependant | Guardian flows (V28); never offered through the generic withdraw endpoint |
| `treatment` | unchanged | Existing procedure/treatment consent | Not part of the registry purposes |

Deferred: an optional "where did you buy / hear" answer (CEO-25, `bought_where`). It is a survey answer, not consent; if the
founder wants it stored as a purpose it is one more CHECK value. Not added here.

**Legacy gap (explicit):** until IDC-004 captures consent at registration, `CONSENT_GATE` keeps "no `data_processing` row =
visible", otherwise every existing patient would vanish. `ConsentRegistry.isGranted` is already default-deny for every purpose.

## 3. Data model

`consents` stays append-only: a grant is a row, a withdrawal is a newer row with `withdrawn_at`; the newest row per
(patient, purpose) decides. New nullable columns (legacy rows keep NULL, no data migration):

| Column | Rule |
|---|---|
| `notice_version`, `language` | both set or both NULL (`consents_notice_pair_check`); with `tenant_id` they are a **foreign key** to `privacy_notices (tenant_id, version, language)`, so a consent joins exactly one notice row |
| `channel` | `DESK`, `WHATSAPP`, `ASSISTED`, `PORTAL`. (V104 `capture_channel` in_person/phone/written for guardian evidence is left alone) |
| `captured_by` (V104) / `witness_staff_id` | staff who recorded it / second staff for ASSISTED (must differ, enforced in IDC-007) |
| `evidence` jsonb | channel evidence ids only (wamid, OTP verification id); no message text, no PHI |

`privacy_notices (id, tenant_id, version, language, template_key, body_md, mandatory_hash, effective_from, created_by, created_at)`,
unique `(tenant_id, version, language)`, RLS enabled and forced through `enforce_tenant_rls`. Rows are **immutable**
(trigger refuses UPDATE): editing a notice means publishing a new version. `consent_type` gains `marketing`.

## 4. Notice versioning and re-acknowledgement

1. A clinic gets its first notice version seeded from the Nabd market template (`template_key`, e.g. `IN-DPDP-1`).
2. Publishing a new version never rewrites old consents; they keep pointing at the text the patient saw.
3. When the clinic's current version is newer than the one on a patient's latest grant, the desk shows "re-acknowledge" at the
   next visit. It does not block care and does not withdraw anything.
4. Withdrawal stops processing/messaging for that purpose and never deletes clinical records.

## 5. Notice template design: editable vs locked

A template = ordered clauses, each `locked` or `editable`, one template per market.

- **Editable by the clinic:** contact details, grievance/complaints contact, clinic name, and *appended* text after the last locked clause.
- **Locked:** every mandatory clause for the market. The server computes `mandatory_hash` = SHA-256 over the locked clauses in
  order; publishing a version whose hash differs from the template's current hash is refused (IDC-004 implements the check).
  Clauses cannot be deleted, reordered or reworded; the clinic's text can only be added.
- **Markets and the law each template must satisfy:** IN: DPDP Act 2023; SA: PDPL; AE: Federal Law 2/2019; QA: Law 13/2016.
- **The mandatory clause wording is legal text and is not drafted here.** It must come from the DPO/counsel per market
  (Needs external action). Nothing in this repo may be presented as statutory wording until then.

## 6. Threat model

Full STRIDE and LINDDUN: `docs/security/threat-models/EP08-consent.md`. The four threats this story must cover, with the
mitigation in this model:

| Threat | Mitigation |
|---|---|
| Forged channel evidence (fake wamid) | `evidence` is written only from signature-verified, persisted inbound events; the desk cannot set `channel = WHATSAPP` (T-103, T-137) |
| Replayed OTP | OTP single-use, expiring, rate-limited per phone; the verification id is stored in `evidence` and is unique per consent (T-052, T-137) |
| Staff captures consent without the patient present | `channel` and `captured_by` always recorded; `ASSISTED` needs `witness_staff_id` different from `captured_by`; capture is audited (T-138, IDC-007) |
| Notice edited after capture | `privacy_notices` is immutable (trigger) and consents reference it by composite FK; mandatory clauses locked by hash |

## 7. Review checklist: every purpose maps to a gate

| Purpose | Gate that must exist | Where |
|---|---|---|
| data_processing | read gate + registration capture | `CONSENT_GATE` now; IDC-004/005 for capture |
| messaging | send gate, re-checked at send | `WhatsAppMessageRepository.hasMessagingConsent` now; `ConsentRegistry` for new code |
| marketing | campaign gate | future marketing story; registry returns deny until a grant row exists |
| guardian_access | guardian flows | `PatientService` guardian methods (V28) |

## 8. Consequences and risks

- Depends on the DPO being hired (D10). If marketing stays inside `messaging`, nothing breaks: purposes are rows.
- `consent_type` is the purpose column; the story's separate `purpose` column was dropped to avoid two sources of truth.
- Mandatory clauses and notice seeds are empty until legal text arrives.

## 9. Sign-off

| Role | Name | Decision | Date |
|---|---|---|---|
| DPO | _pending_ | _pending_ | _pending_ |

Open items for the DPO: approve purposes and all-deny defaults; supply mandatory clauses for IN, SA, AE, QA; decide `bought_where`.
