SET lock_timeout = '5s';
-- COM-003: two-phase template create. The row is inserted as 'submitting' BEFORE Meta is called, so a timeout or a
-- failed write afterwards leaves a row the reconciler can adopt (Meta has it) or delete (Meta never got it).
ALTER TABLE whatsapp_templates DROP CONSTRAINT whatsapp_templates_status_check;
ALTER TABLE whatsapp_templates ADD CONSTRAINT whatsapp_templates_status_check
  CHECK (status IN ('submitting', 'pending', 'approved', 'flagged', 'paused', 'disabled', 'rejected'));
ALTER TABLE whatsapp_templates ADD COLUMN submitted_at timestamptz;

-- The reconciler works across tenants; the API role is RLS-bound, so it lists stale rows through this one narrow
-- SECURITY DEFINER function (ids and names only, no template text).
CREATE FUNCTION stale_submitting_templates(p_minutes int, p_limit int)
RETURNS TABLE (tenant_id uuid, id uuid, meta_name text, language text)
LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
  SELECT t.tenant_id, t.id, t.meta_name, t.language FROM whatsapp_templates t
  WHERE t.status = 'submitting' AND t.submitted_at < now() - make_interval(mins => p_minutes)
  ORDER BY t.submitted_at LIMIT p_limit
$$;
REVOKE ALL ON FUNCTION stale_submitting_templates(int, int) FROM PUBLIC;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION stale_submitting_templates(int, int) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
