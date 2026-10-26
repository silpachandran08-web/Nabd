-- COM-002: WhatsApp STOP opt-out. (1) consents learns the 'marketing' purpose plus where a consent change came from and which
-- privacy-notice version it was given under. (2) messaging_suppressions records an opt-out by phone, for a STOP from a number
-- with no patient record yet (and for the shared-number STOP that names no clinic: tenant_id NULL = every clinic).
-- The phone is stored as an HMAC-SHA256 hash, never the number. Every access is a narrow SECURITY DEFINER function because
-- tenant_id NULL rows are invisible to the app role under RLS.
SET lock_timeout = '5s';

-- Extend the purpose list in place instead of restating it, so purposes other migrations added stay allowed.
DO $$
DECLARE d text;
BEGIN
  SELECT pg_get_constraintdef(oid) INTO d FROM pg_constraint
   WHERE conrelid = 'consents'::regclass AND conname = 'consents_consent_type_check';
  IF d IS NULL OR position('ARRAY[' IN d) = 0 THEN
    RAISE EXCEPTION 'consents_consent_type_check has an unexpected shape: %', d;
  END IF;
  IF position('''marketing''' IN d) = 0 THEN
    ALTER TABLE consents DROP CONSTRAINT consents_consent_type_check;
    EXECUTE format('ALTER TABLE consents ADD CONSTRAINT consents_consent_type_check %s',
                   replace(d, 'ARRAY[', 'ARRAY[''marketing''::text, '));
  END IF;
END $$;
ALTER TABLE consents ADD COLUMN IF NOT EXISTS source text;          -- e.g. whatsapp_stop, whatsapp_start; NULL for staff-recorded rows
ALTER TABLE consents ADD COLUMN IF NOT EXISTS notice_version text;  -- privacy-notice version the grant was given under

CREATE TABLE messaging_suppressions (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id  uuid REFERENCES tenants(id),                  -- NULL = all clinics on the shared number
  phone_hash bytea NOT NULL,
  purpose    text NOT NULL CHECK (purpose IN ('messaging', 'marketing')),
  source     text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  lifted_at  timestamptz
);
CREATE UNIQUE INDEX uq_messaging_suppressions_active
  ON messaging_suppressions (COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), phone_hash, purpose)
  WHERE lifted_at IS NULL;

CREATE FUNCTION add_messaging_suppression(p_tenant uuid, p_hash bytea, p_purpose text, p_source text)
RETURNS boolean
LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
  WITH i AS (
    INSERT INTO messaging_suppressions (tenant_id, phone_hash, purpose, source) VALUES (p_tenant, p_hash, p_purpose, p_source)
    ON CONFLICT (COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), phone_hash, purpose) WHERE lifted_at IS NULL
    DO NOTHING
    RETURNING 1)
  SELECT EXISTS (SELECT 1 FROM i)
$$;
REVOKE ALL ON FUNCTION add_messaging_suppression(uuid, bytea, text, text) FROM PUBLIC;

-- START lifts the suppressions of the same scope only: a clinic's START never lifts the all-clinics one, and vice versa.
CREATE FUNCTION lift_messaging_suppression(p_tenant uuid, p_hash bytea, p_purpose text)
RETURNS int
LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
  WITH u AS (
    UPDATE messaging_suppressions SET lifted_at = now()
    WHERE phone_hash = p_hash AND purpose = p_purpose AND lifted_at IS NULL AND tenant_id IS NOT DISTINCT FROM p_tenant
    RETURNING 1)
  SELECT count(*)::int FROM u
$$;
REVOKE ALL ON FUNCTION lift_messaging_suppression(uuid, bytea, text) FROM PUBLIC;

-- Send-time check: suppressed for this clinic, or for every clinic.
CREATE FUNCTION messaging_suppressed(p_tenant uuid, p_hash bytea, p_purpose text)
RETURNS boolean
LANGUAGE sql SECURITY DEFINER STABLE SET search_path = public
AS $$
  SELECT EXISTS (SELECT 1 FROM messaging_suppressions
                 WHERE phone_hash = p_hash AND purpose = p_purpose AND lifted_at IS NULL
                   AND (tenant_id = p_tenant OR tenant_id IS NULL))
$$;
REVOKE ALL ON FUNCTION messaging_suppressed(uuid, bytea, text) FROM PUBLIC;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION add_messaging_suppression(uuid, bytea, text, text) TO nabd_app;
    GRANT EXECUTE ON FUNCTION lift_messaging_suppression(uuid, bytea, text) TO nabd_app;
    GRANT EXECUTE ON FUNCTION messaging_suppressed(uuid, bytea, text) TO nabd_app;
  END IF;
END $$;

SELECT enforce_tenant_rls('messaging_suppressions');
SELECT nabd_adopt_definer_functions();
