SET lock_timeout = '5s';
-- CLN-023: the signed Rx QR token, the stored Rx PDFs and revocation. A table of its own instead of new prescriptions columns
-- (the story's risk note): V51 makes a signed prescriptions row final, and everything here is written after signing, so the
-- signed row itself never changes. One row per signed prescription, issued at sign (or on first print for an Rx signed
-- before this migration). rx_token is the PHL-013 NRX1 payload (ids, times, kid, registration no, ES256 signature; no names,
-- no medicines). payload_sha256 = SHA-256 of the signed text, the reference events carry instead of the token.
-- pdf_*: the file_objects row of each stored PDF size and its SHA-256, set once; revoked_*: set once by the prescriber.
CREATE TABLE prescription_rx_tokens (
  prescription_id uuid PRIMARY KEY REFERENCES prescriptions (id),
  tenant_id       uuid NOT NULL REFERENCES tenants (id),
  rx_token        text NOT NULL UNIQUE,
  payload_sha256  text NOT NULL,
  signing_kid     text NOT NULL,
  valid_until     timestamptz,
  pdf_a4_file_id  uuid,
  pdf_a4_sha256   text,
  pdf_a5_file_id  uuid,
  pdf_a5_sha256   text,
  revoked_at      timestamptz,
  revoke_reason   text,
  revoked_by      uuid REFERENCES staff (id),
  created_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT prescription_rx_tokens_pdf_a4 CHECK ((pdf_a4_file_id IS NULL) = (pdf_a4_sha256 IS NULL)),
  CONSTRAINT prescription_rx_tokens_pdf_a5 CHECK ((pdf_a5_file_id IS NULL) = (pdf_a5_sha256 IS NULL)),
  CONSTRAINT prescription_rx_tokens_revoked CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL) AND (revoked_at IS NULL) = (revoked_by IS NULL))
);
SELECT enforce_tenant_rls('prescription_rx_tokens');

-- Backstop like V51: the token never changes, a stored PDF is never swapped, a revocation is never undone, no row is deleted.
CREATE OR REPLACE FUNCTION prescription_rx_tokens_write_once() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'prescription_rx_tokens rows cannot be deleted' USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF NEW.prescription_id <> OLD.prescription_id OR NEW.tenant_id <> OLD.tenant_id OR NEW.rx_token <> OLD.rx_token
     OR NEW.payload_sha256 <> OLD.payload_sha256 OR NEW.signing_kid <> OLD.signing_kid OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
     OR (OLD.pdf_a4_file_id IS NOT NULL AND (NEW.pdf_a4_file_id IS DISTINCT FROM OLD.pdf_a4_file_id OR NEW.pdf_a4_sha256 IS DISTINCT FROM OLD.pdf_a4_sha256))
     OR (OLD.pdf_a5_file_id IS NOT NULL AND (NEW.pdf_a5_file_id IS DISTINCT FROM OLD.pdf_a5_file_id OR NEW.pdf_a5_sha256 IS DISTINCT FROM OLD.pdf_a5_sha256))
     OR (OLD.revoked_at IS NOT NULL AND (NEW.revoked_at IS DISTINCT FROM OLD.revoked_at OR NEW.revoke_reason IS DISTINCT FROM OLD.revoke_reason
                                         OR NEW.revoked_by IS DISTINCT FROM OLD.revoked_by)) THEN
    RAISE EXCEPTION 'the Rx token, its stored PDFs and its revocation are write-once' USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prescription_rx_tokens_write_once BEFORE UPDATE OR DELETE ON prescription_rx_tokens
  FOR EACH ROW EXECUTE FUNCTION prescription_rx_tokens_write_once();

-- The prescriber's council (printed next to the registration number) and uploaded signature image (a file_objects row).
ALTER TABLE staff ADD COLUMN IF NOT EXISTS registration_council text;
ALTER TABLE staff ADD COLUMN IF NOT EXISTS signature_file_id uuid;

-- The signature image is a client upload (scanned like any other); the Rx PDF is server-rendered.
ALTER TABLE file_objects DROP CONSTRAINT file_objects_purpose_check;
ALTER TABLE file_objects ADD CONSTRAINT file_objects_purpose_check
  CHECK (purpose IN ('outside_report', 'clinical_photo', 'xray', 'consent_form', 'claim_pack', 'signature', 'prescription')) NOT VALID;
ALTER TABLE file_objects VALIDATE CONSTRAINT file_objects_purpose_check;
