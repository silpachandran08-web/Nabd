SET lock_timeout = '5s';
-- PHL-013: audit of every Rx QR scan at the pharmacy counter. prescription_id is NULL when the QR did not verify (altered,
-- unknown key, another clinic's Rx, unknown prescription). result: valid, expired, dispensed, invalid. Append only.
CREATE TABLE rx_verifications (
  id              uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id       uuid NOT NULL REFERENCES tenants (id),
  prescription_id uuid,
  result          text NOT NULL CHECK (result IN ('valid', 'expired', 'dispensed', 'invalid')),
  reason          text,
  scanned_by      uuid NOT NULL REFERENCES staff (id),
  scanned_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_rx_verifications_rx ON rx_verifications (tenant_id, prescription_id, scanned_at);
SELECT enforce_tenant_rls('rx_verifications');
