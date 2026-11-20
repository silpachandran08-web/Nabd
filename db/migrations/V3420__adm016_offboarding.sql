SET lock_timeout = '5s';
-- ADM-016 (NFR-18, SYS-23): a leaving owner asks for deletion, has 7 days to cancel, then the clinic's rows are erased, its data
-- key destroyed and a signed certificate issued.

-- The owner's request. One open request per clinic. step_up_id / cancel_step_up_id are the jti of the confirmation that asked
-- for / cancelled it (unique: a confirmation works once). requested_by and export_id carry no foreign key on purpose: the staff
-- row and the export job are erased with the clinic, this row stays as the record of the erasure. notify_email is the owner's
-- address taken at request time and cleared once the certificate has been sent.
CREATE TABLE offboarding_requests (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           uuid NOT NULL REFERENCES tenants(id),
  requested_by        uuid NOT NULL,
  export_id           uuid,
  waived              boolean NOT NULL DEFAULT false,
  reason              text NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
  previous_status     text NOT NULL,
  step_up_id          uuid NOT NULL UNIQUE,
  cancel_step_up_id   uuid UNIQUE,
  notify_email        text,
  status              text NOT NULL DEFAULT 'cooling_off' CHECK (status IN ('cooling_off', 'erasing', 'done', 'cancelled')),
  requested_at        timestamptz NOT NULL DEFAULT now(),
  cooling_off_ends_at timestamptz NOT NULL,
  cancelled_at        timestamptz,
  erased_at           timestamptz,
  row_counts          jsonb,
  certificate_id      uuid,
  CONSTRAINT offboarding_requests_gate CHECK (waived OR export_id IS NOT NULL)
);
CREATE UNIQUE INDEX uq_offboarding_requests_open ON offboarding_requests (tenant_id) WHERE status IN ('cooling_off', 'erasing');
SELECT enforce_tenant_rls('offboarding_requests');

-- The certificate, kept in the control plane (master) after the clinic's rows are gone. No PHI: ids, the clinic's name, dates,
-- region and row counts per table. signed_doc is the certificate; signature is ES256 (raw r||s, base64url) over its RFC 8785
-- (JCS) form with key kid; doc_sha256 is the SHA-256 of that form and opens the public link.
CREATE TABLE master.deletion_certificates (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  brand_id        uuid,
  request_id      uuid NOT NULL UNIQUE,
  requested_at    timestamptz NOT NULL,
  erased_at       timestamptz NOT NULL,
  backup_purge_at timestamptz NOT NULL,
  region          text NOT NULL,
  row_counts      jsonb NOT NULL,
  signed_doc      jsonb NOT NULL,
  signature       text NOT NULL,
  kid             text NOT NULL,
  doc_sha256      text NOT NULL CHECK (doc_sha256 ~ '^[0-9a-f]{64}$'),
  issued_at       timestamptz NOT NULL DEFAULT now()
);
SELECT enforce_tenant_rls('master.deletion_certificates');

-- Operators and the holder of the link read a certificate with no clinic bound (same model as data_export_link_tenant, V3001).
CREATE FUNCTION deletion_certificate(p_id uuid)
RETURNS TABLE (id uuid, tenant_id uuid, signed_doc text, signature text, kid text, doc_sha256 text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT c.id, c.tenant_id, c.signed_doc::text, c.signature, c.kid, c.doc_sha256 FROM master.deletion_certificates c WHERE c.id = p_id
$$;
REVOKE ALL ON FUNCTION deletion_certificate(uuid) FROM PUBLIC;

-- The erasure. It takes NO tenant argument: it erases the clinic bound to the transaction (app.tenant_id) and only when that
-- clinic's own request is in 'erasing', its cooling-off has ended and the clinic is 'offboarded'. So a caller bound to clinic A
-- can never erase clinic B, and no clinic can be erased without its owner's request and the 7 days.
-- Every public table with a tenant_id column is emptied for that clinic (the list is read from the catalogue at run time, so a
-- table added later is covered), in ONE statement: foreign keys are checked at its end, so no delete order is needed.
-- Kept: this request, tenant_data_keys (destroyed, empty), tenant_lifecycle_events and the tenants row itself (contact fields
-- cleared); master.* is Nabd's own record of the customer (subscription, platform invoices, operator audit), except that support
-- tickets lose their free text and the staff link. Returns rows deleted per table; a second call returns the first call's counts.
-- shortcut: one statement for the whole clinic; split per table in foreign-key order when a clinic passes about a million rows.
CREATE FUNCTION erase_offboarded_tenant() RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_tenant uuid := app_tenant_id();
  v_req    offboarding_requests%ROWTYPE;
  v_with   text := 'WITH d0 AS (DELETE FROM cross_tenant_ref_quarantine WHERE child_tenant_id = $1 OR parent_tenant_id = $1 RETURNING 1)';
  v_counts text := 'SELECT ''cross_tenant_ref_quarantine'' AS t, count(*) AS n FROM d0';
  v_result jsonb;
  t        record;
  i        int := 0;
BEGIN
  IF v_tenant IS NULL THEN
    RAISE EXCEPTION 'erase_offboarded_tenant: no clinic is bound';
  END IF;
  SELECT * INTO v_req FROM offboarding_requests WHERE tenant_id = v_tenant AND status = 'erasing' FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'erase_offboarded_tenant: clinic % has no offboarding request in erasure', v_tenant;
  END IF;
  IF v_req.erased_at IS NOT NULL THEN
    RETURN v_req.row_counts;
  END IF;
  IF v_req.cooling_off_ends_at > now() THEN
    RAISE EXCEPTION 'erase_offboarded_tenant: clinic % is still in its cooling-off period', v_tenant;
  END IF;
  IF (SELECT status FROM tenants WHERE id = v_tenant) IS DISTINCT FROM 'offboarded' THEN
    RAISE EXCEPTION 'erase_offboarded_tenant: clinic % is not offboarded', v_tenant;
  END IF;

  PERFORM set_config('app.tenant_erasure', v_tenant::text, true); -- lets the append-only triggers below stand aside
  UPDATE master.support_tickets SET raised_by_staff_id = NULL, raised_by_name = '[erased]', raised_by_email = NULL,
         subject = '[erased]', description = '[erased]' WHERE tenant_id = v_tenant;
  UPDATE tenants SET whatsapp_number = NULL, consent_contact_name = NULL, consent_contact_email = NULL, consent_contact_phone = NULL
   WHERE id = v_tenant;
  FOR t IN SELECT k.relname AS name FROM pg_class k
            WHERE k.relnamespace = 'public'::regnamespace AND k.relkind IN ('r', 'p') AND NOT k.relispartition
              AND k.relname NOT IN ('offboarding_requests', 'tenant_data_keys', 'tenant_lifecycle_events')
              AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = k.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped)
            ORDER BY k.relname
  LOOP
    i := i + 1;
    v_with := v_with || format(', d%s AS (DELETE FROM public.%I WHERE tenant_id = $1 RETURNING 1)', i, t.name);
    v_counts := v_counts || format(' UNION ALL SELECT %L, count(*) FROM d%s', t.name, i);
  END LOOP;
  EXECUTE v_with || ' SELECT COALESCE(jsonb_object_agg(x.t, x.n), ''{}''::jsonb) FROM (' || v_counts || ') x WHERE x.n > 0'
     INTO v_result USING v_tenant;
  PERFORM set_config('app.tenant_erasure', '', true);
  UPDATE offboarding_requests SET erased_at = now(), row_counts = v_result WHERE id = v_req.id;
  RETURN v_result;
END $$;
REVOKE ALL ON FUNCTION erase_offboarded_tenant() FROM PUBLIC;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION deletion_certificate(uuid) TO nabd_app;
    GRANT EXECUTE ON FUNCTION erase_offboarded_tenant() TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();

-- True only inside erase_offboarded_tenant(): the statement runs as that function's owner (nabd_definer, which the API role is
-- not a member of) AND the function has set app.tenant_erasure. The API role can set the GUC but cannot be that owner.
CREATE FUNCTION nabd_tenant_erasure_caller() RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT current_user = (SELECT r.rolname FROM pg_proc p JOIN pg_roles r ON r.oid = p.proowner
                          WHERE p.oid = 'public.erase_offboarded_tenant()'::regprocedure)
$$;

-- The delete triggers on clinic tables (append-only guards such as audit_log, signed prescriptions, PHI read log, and the
-- registry dirty-markers that would write new rows for the clinic) stand aside during an erasure and only then. Each trigger is
-- re-created from its own definition with a WHEN clause; the GUC test comes first, so normal traffic pays one setting lookup.
-- A delete trigger added to a clinic table later must carry the same WHEN clause (Adm016OffboardingApiTest checks it).
DO $$
DECLARE
  g   record;
  def text;
BEGIN
  FOR g IN SELECT t.tgname, t.tgrelid::regclass AS tbl, pg_get_triggerdef(t.oid) AS def
             FROM pg_trigger t JOIN pg_class k ON k.oid = t.tgrelid
            WHERE NOT t.tgisinternal AND t.tgparentid = 0 AND (t.tgtype & 8) = 8 AND t.tgqual IS NULL
              AND k.relnamespace = 'public'::regnamespace
              AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = t.tgrelid AND a.attname = 'tenant_id' AND NOT a.attisdropped)
  LOOP
    def := replace(g.def, ' EXECUTE FUNCTION ',
                   ' WHEN (NOT (COALESCE(current_setting(''app.tenant_erasure'', true), '''') <> '''' AND nabd_tenant_erasure_caller())) EXECUTE FUNCTION ');
    IF def = g.def THEN
      RAISE EXCEPTION 'ADM-016: trigger % on % has a definition this migration does not rewrite', g.tgname, g.tbl;
    END IF;
    EXECUTE format('DROP TRIGGER %I ON %s', g.tgname, g.tbl);
    EXECUTE def;
  END LOOP;
END $$;
