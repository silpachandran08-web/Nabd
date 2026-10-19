-- FIN-004: one open refund per package instance, a reject path, and the amount recomputed at approval.
ALTER TABLE package_refunds DROP CONSTRAINT IF EXISTS package_refunds_status_check;
ALTER TABLE package_refunds ADD CONSTRAINT package_refunds_status_check
  CHECK (status IN ('pending', 'approved', 'rejected'));
ALTER TABLE package_refunds
  ADD COLUMN IF NOT EXISTS rejected_by uuid REFERENCES staff(id),
  ADD COLUMN IF NOT EXISTS rejected_at timestamptz,
  ADD COLUMN IF NOT EXISTS reject_reason text,
  ADD COLUMN IF NOT EXISTS recomputed_amount numeric(12,2);

-- QA-15 left duplicate pending requests behind: keep the oldest per instance, reject the rest so the index can build.
-- RLS is forced and the migrator has no BYPASSRLS, so scope each tenant the way the API does (as V312).
DO $$
DECLARE t record;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);
    PERFORM set_config('app.visible_tenant_ids', t.id::text, true);
    UPDATE package_refunds r SET status = 'rejected', rejected_at = now(),
           reject_reason = 'Duplicate open request closed by FIN-004 migration'
     WHERE r.status = 'pending'
       AND EXISTS (SELECT 1 FROM package_refunds o
                    WHERE o.tenant_id = r.tenant_id AND o.instance_id = r.instance_id AND o.status = 'pending'
                      AND (o.created_at, o.id) < (r.created_at, r.id));
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.visible_tenant_ids', '', true);
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_package_refunds_one_pending
  ON package_refunds (tenant_id, instance_id) WHERE status = 'pending';

SELECT enforce_tenant_rls('package_refunds');
