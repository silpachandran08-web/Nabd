SET lock_timeout = '5s';
-- ADM-037 follow-up (OPS-003, non-negotiable 6.2): master.subscription_changes and master.platform_invoices are the clinic's own
-- rows, read and written only by the owner's /v1/setup/subscription endpoints in the clinic's tenant transaction, so they get
-- forced tenant RLS with the four per-command policies instead of an rls-exemptions.txt entry. No platform-console path reads them.
SELECT enforce_tenant_rls('master.subscription_changes');
SELECT enforce_tenant_rls('master.platform_invoices');

-- The hourly job claims due downgrades across clinics before it enters each clinic's scope (same precedent as
-- claim_outbox_events, V44): a definer that returns plan-change rows only (plan ids, amounts, no PHI); each one is then applied
-- in TenantScope.run for its own tenant.
CREATE FUNCTION claim_due_plan_changes(p_now timestamptz)
RETURNS TABLE (id uuid, tenant_id uuid, to_plan_id uuid, to_plan_version int, to_plan_code text, direction text, effective text,
               effective_at timestamptz, proration_minor bigint, currency text, status text)
LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
  WITH due AS (SELECT d.id FROM master.subscription_changes d WHERE d.status = 'scheduled' AND d.effective_at <= p_now
               FOR UPDATE SKIP LOCKED)
  UPDATE master.subscription_changes c SET status = 'applied', applied_at = now() FROM due, master.plans p
  WHERE c.id = due.id AND p.id = c.to_plan_id
  RETURNING c.id, c.tenant_id, c.to_plan_id, c.to_plan_version, p.code, c.direction, c.effective, c.effective_at, c.proration_minor,
            c.currency, c.status;
$$;
REVOKE ALL ON FUNCTION claim_due_plan_changes(timestamptz) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION claim_due_plan_changes(timestamptz) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
