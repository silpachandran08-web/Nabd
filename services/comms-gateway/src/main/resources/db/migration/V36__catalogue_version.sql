SET lock_timeout = '5s';
-- FEF-041: the patient catalogue artefact version (PatientCatalogue.version(), a content hash of packages/i18n's published
-- patient-catalogue.json) in force when the message was queued. NULL = sent before FEF-041. No text, no PHI: a short hash.
ALTER TABLE outbound_messages ADD COLUMN catalogue_version text;
