SET lock_timeout = '5s';
-- FIN-011: e-invoice submissions (one row per document and regime) and the per-clinic IRP switch. The worker is shared by the
-- regimes the check lists (IRP now; ZATCA and Peppol later): a row waits in 'pending' with next_attempt_at, a claim pushes
-- next_attempt_at forward (backoff) and leaves the row pending, so a crashed attempt is retried by itself.
-- request_object_key / response_object_key are for the payload archive in the encrypted object store (not written yet).
CREATE TABLE einvoice_documents (
  id                  uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id           uuid NOT NULL REFERENCES tenants (id),
  document_type       text NOT NULL CHECK (document_type IN ('invoice', 'credit_note', 'debit_note')),
  document_id         uuid NOT NULL,
  regime              text NOT NULL CHECK (regime IN ('IN_IRP', 'SA_ZATCA', 'AE_PEPPOL')),
  status              text NOT NULL DEFAULT 'pending'
                      CHECK (status IN ('pending', 'submitted', 'accepted', 'cleared', 'reported', 'rejected', 'cancelled')),
  external_id         text,          -- IRN (IRP) / UUID (ZATCA)
  ack_ref             text,          -- IRP Ack No
  ack_at              timestamptz,   -- IRP Ack date: the 24 h cancellation window starts here
  qr_payload          text,          -- the signed QR as the IRP returned it
  request_object_key  text,
  response_object_key text,
  errors              jsonb,         -- the last failure: {kind, codes, message, at}
  attempts            int NOT NULL DEFAULT 0,
  next_attempt_at     timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, regime, document_type, document_id)
);
CREATE INDEX idx_einvoice_documents_due ON einvoice_documents (tenant_id, status, next_attempt_at);
SELECT enforce_tenant_rls('einvoice_documents');

-- No row = e-invoicing is off (the default: most clinics are below the turnover threshold, FRD CEO-06).
CREATE TABLE einvoice_settings (
  tenant_id   uuid PRIMARY KEY REFERENCES tenants (id),
  irp_enabled boolean NOT NULL DEFAULT false,
  updated_by  uuid,
  updated_at  timestamptz NOT NULL DEFAULT now()
);
SELECT enforce_tenant_rls('einvoice_settings');

-- The worker asks which clinics have a submission due before it enters each clinic's own scope (same precedent as
-- claim_due_plan_changes, V3061). Returns tenant ids only, and only for clinics whose switch is on.
CREATE FUNCTION einvoice_due_tenants(p_now timestamptz)
RETURNS TABLE (tenant_id uuid)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT DISTINCT d.tenant_id
    FROM einvoice_documents d JOIN einvoice_settings s ON s.tenant_id = d.tenant_id AND s.irp_enabled
   WHERE d.status = 'pending' AND d.next_attempt_at <= p_now
$$;
REVOKE ALL ON FUNCTION einvoice_due_tenants(timestamptz) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION einvoice_due_tenants(timestamptz) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
