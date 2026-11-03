SET lock_timeout = '5s';
-- PHL-012: the in-house pharmacist's worklist. One entry per signed prescription that has at least one in-house line (a stocked,
-- active, in-house item matching the line's coded drug or, for a free-text line, its name; the same rule as the checkout prefill,
-- PHL-018). External lines never enter it. Created by the clinical.rx.signed.v1 consumer, In-house clinics only.
-- lines: [{prescriptionItemId, itemId, qty}] of the in-house lines with a prescribed quantity (a line without one cannot be
-- dispensed against, RxGate). Ids and quantities only: names are read at display time.

-- Rx validity per schedule class (NULL = the class never expires an Rx). No value is set here: the clinical periods need
-- clinical safety officer sign-off before a migration fills them.
ALTER TABLE pharmacy_schedule_rules ADD COLUMN rx_validity_days integer CHECK (rx_validity_days > 0);

CREATE TABLE pharmacy_worklist_entries (
  id              uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id       uuid NOT NULL REFERENCES tenants (id),
  prescription_id uuid NOT NULL,
  patient_id      uuid NOT NULL,
  queue_entry_id  uuid,
  doctor_id       uuid,
  signed_at       timestamptz NOT NULL,
  lines           jsonb NOT NULL CHECK (jsonb_typeof(lines) = 'array'),
  status          text NOT NULL DEFAULT 'new'
                  CHECK (status IN ('new', 'in_progress', 'partially_dispensed', 'dispensed', 'cancelled', 'expired')),
  claimed_by      uuid REFERENCES staff (id),
  claimed_at      timestamptz,
  valid_until     timestamptz,
  created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),  -- distinct per row even when one transaction creates several (keyset order)
  updated_at      timestamptz NOT NULL DEFAULT now(),
  change_seq      bigint,
  UNIQUE (tenant_id, prescription_id),  -- the idempotent consumer's key: a redelivered event finds the row
  CHECK ((claimed_by IS NULL) = (claimed_at IS NULL))
);
CREATE INDEX idx_pharmacy_worklist_status ON pharmacy_worklist_entries (tenant_id, status, created_at);
CREATE TRIGGER trg_pharmacy_worklist_change_seq BEFORE INSERT OR UPDATE ON pharmacy_worklist_entries
  FOR EACH ROW EXECUTE FUNCTION set_change_seq();
SELECT enforce_tenant_rls('pharmacy_worklist_entries');

-- Backfill: In-house clinics' prescriptions signed in the last 7 days with no line dispensed yet (non-void invoice lines).
-- RLS is forced and the migrator has no BYPASSRLS, so scope each tenant the way the API does (as V1232).
DO $$
DECLARE
  t record;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);
    PERFORM set_config('app.visible_tenant_ids', t.id::text, true);
    INSERT INTO pharmacy_worklist_entries (tenant_id, prescription_id, patient_id, queue_entry_id, doctor_id, signed_at, lines)
    SELECT pr.tenant_id, pr.id, pr.patient_id, pr.queue_entry_id, pr.doctor_id, pr.signed_at, l.lines
    FROM prescriptions pr
    JOIN pharmacy_settings s ON s.tenant_id = pr.tenant_id AND s.mode = 'in_house'
    CROSS JOIN LATERAL (
      SELECT jsonb_agg(jsonb_build_object('prescriptionItemId', pi.id, 'itemId', c.id, 'qty', pi.quantity) ORDER BY pi.display_order) AS lines
      FROM prescription_items pi
      JOIN LATERAL (
        SELECT c.id FROM charge_catalogue c
        LEFT JOIN pharmacy_item_profiles p ON p.item_id = c.id AND p.tenant_id = c.tenant_id
        WHERE c.tenant_id = pi.tenant_id AND c.active AND c.stock_qty IS NOT NULL AND COALESCE(p.in_house, true)
          AND ((pi.drug_id IS NOT NULL AND c.drug_id = pi.drug_id) OR lower(btrim(c.name)) = lower(btrim(pi.drug_name)))
        ORDER BY (pi.drug_id IS NOT NULL AND c.drug_id = pi.drug_id) DESC, c.code
        LIMIT 1) c ON true
      WHERE pi.tenant_id = pr.tenant_id AND pi.prescription_id = pr.id AND pi.quantity IS NOT NULL) l
    WHERE pr.tenant_id = t.id AND pr.status = 'signed' AND pr.signed_at >= now() - interval '7 days'
      AND l.lines IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM prescription_items pi
                      JOIN invoice_line_items li ON li.tenant_id = pi.tenant_id AND li.prescription_item_id = pi.id
                      JOIN invoices i ON i.id = li.invoice_id AND i.tenant_id = li.tenant_id
                      WHERE pi.tenant_id = pr.tenant_id AND pi.prescription_id = pr.id AND i.status <> 'void')
    ON CONFLICT (tenant_id, prescription_id) DO NOTHING;
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.visible_tenant_ids', '', true);
END $$;
