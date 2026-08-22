-- E09 Caregiver, Family & Guardianship.
--
-- Scope: most of this epic's tickets (NB-080, 083, 084, 086, 087, 088) describe a WhatsApp
-- in-chat flow — "single member number shows no picker", "five-step in-chat flow", "six-tap
-- budget". No WhatsApp/chat channel exists anywhere in this codebase (NB-201, NB-197, NB-202 are
-- all themselves "Not started"), so those can't be built for real here without first inventing
-- that whole channel — a separate, much larger project. What's built instead is the real,
-- already-reachable part: the guardian/proxy relationship already on patients.guardian_id (NB-071)
-- gets consent capture (NB-081, non-chat portion), an age-18 review worklist (NB-082), and
-- revocation (NB-085) — all through the existing Patients module and its existing consents table
-- (NB-053), rather than a new one.
--
-- 'guardian_access' is a new consent_type on the existing table (V5). Deliberately NOT added to
-- WithdrawConsentRequest's API-level regex: that generic endpoint only marks a consents row
-- withdrawn and does not touch patients.guardian_id, so allowing it there would let a caller
-- create an inconsistent state (consent withdrawn, guardian_id still set). guardian_access is
-- only ever written by PatientService, atomically with the guardian_id change that caused it.
ALTER TABLE consents DROP CONSTRAINT consents_consent_type_check;
ALTER TABLE consents ADD CONSTRAINT consents_consent_type_check
  CHECK (consent_type IN ('treatment', 'data_processing', 'messaging', 'guardian_access'));
