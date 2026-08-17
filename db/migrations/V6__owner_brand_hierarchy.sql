-- Owner -> Brand -> Clinic hierarchy above the existing clinic-scoped schema.
-- Clinic (tenants, unchanged) stays the RLS boundary — nothing about
-- patients/staff/appointments/queue changes. owners and brands get NO RLS
-- of their own, same precedent as tenants itself (see V1): nothing above
-- the per-row-scoped tables needs row security, since access is an
-- explicit WHERE owner_id = ? / WHERE brand_id = ? in application queries,
-- not implicit session-context filtering.

-- ── owners ──
-- Top-level account. Not a row in any clinic's staff table — deliberately
-- separate from `staff`, per the "Owner is a new top-level entity" decision.
CREATE TABLE owners (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name        text NOT NULL,
  email       citext NOT NULL UNIQUE,   -- global uniqueness: an Owner isn't tenant-scoped, so no (tenant,email) pair to key off
  pin_hash    text,                     -- null until first login/activation, mirrors staff.pin_hash
  status      text NOT NULL DEFAULT 'active' CHECK (status IN ('active','suspended')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_owners_updated_at BEFORE UPDATE ON owners
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── brands ──
CREATE TABLE brands (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id    uuid NOT NULL REFERENCES owners(id),
  name        text NOT NULL,
  status      text NOT NULL DEFAULT 'active' CHECK (status IN ('active','suspended')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (owner_id, name)
);
CREATE TRIGGER trg_brands_updated_at BEFORE UPDATE ON brands
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE INDEX idx_brands_owner ON brands (owner_id);

-- ── tenants (Clinic) gains a parent ──
-- Nullable for now: no provisioning flow exists yet that would populate
-- this at insert time (including the test suite's seedTenant() helper).
-- Tightening to NOT NULL once that flow exists is a one-line ALTER later,
-- not a rewrite — deliberately not doing it now to avoid breaking every
-- existing seed path for a column nothing yet requires.
ALTER TABLE tenants ADD COLUMN brand_id uuid REFERENCES brands(id);
CREATE INDEX idx_tenants_brand ON tenants (brand_id);

-- ── staff gains an optional link back to the Owner who "is" this row ──
-- When an Owner selects a clinic workspace, the app finds-or-creates one
-- staff row per (owner, clinic) — reusing 100% of the existing staff/roles/
-- RLS/JWT/audit-FK machinery instead of teaching every controller a second
-- caller-identity type. owner_id is NULL for every ordinary staff member
-- (Doctor, Reception, Nurse, ...); set only on these Owner-linked rows.
ALTER TABLE staff ADD COLUMN owner_id uuid REFERENCES owners(id);
CREATE UNIQUE INDEX uq_staff_tenant_owner ON staff (tenant_id, owner_id) WHERE owner_id IS NOT NULL;

-- ── login_attempts gains the same optional owner link, for Owner lockout ──
ALTER TABLE login_attempts ADD COLUMN owner_id uuid REFERENCES owners(id);
CREATE INDEX idx_login_attempts_owner_time ON login_attempts (owner_id, attempted_at DESC) WHERE owner_id IS NOT NULL;

-- ── RLS: let an Owner session see every clinic under their brands ──
-- Today, exactly one clinic ID is ever "current" (app.tenant_id). A future
-- brand-wide dashboard needs rows from several clinics at once, so every
-- existing tenant_isolation policy gains one clause: also allow rows whose
-- tenant_id appears in app.accessible_tenant_ids, a second session variable
-- (comma-separated — Postgres GUCs are text-only, no native array type)
-- that this pass's application code never actually sets yet (only the
-- single-clinic shadow-staff login path is wired up below) — the RLS side
-- ships now so the DB layer is ready whenever that dashboard is built,
-- without touching every policy a second time. A normal staff/Doctor/Owner-
-- viewing-one-clinic session never sets it, current_setting(...) returns
-- NULL, and behavior is byte-for-byte identical to today.
--
-- Two landmines this is deliberately built to avoid, both already hit once
-- in this codebase this session (see PatientRepository's NULL-cast bug and
-- TenantContext's empty-string GUC note):
--   1. NULLIF(...,'') — a custom GUC that was set earlier on a pooled
--      connection reverts to '' (empty string), not NULL, once that
--      transaction ends. string_to_array('', ',') would otherwise produce
--      {''} instead of NULL, and comparisons against it silently fail
--      rather than erroring, which is worse: quietly-wrong access instead
--      of a loud failure.
--   2. Comparing tenant_id::text against a text[] instead of casting the
--      array to uuid[] — casting '' to uuid throws; casting '' to text
--      does not.
--
-- Repeated per table because Postgres has no "apply to every RLS policy
-- matching a name" DDL shortcut. Verified empirically (throwaway Postgres,
-- not just read) before landing: normal session unaffected, multi-clinic
-- session sees exactly its accessible set, the empty-string case doesn't
-- throw, an untouched connection sees nothing.

ALTER POLICY tenant_isolation ON roles USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);
ALTER POLICY tenant_isolation ON staff USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);
ALTER POLICY tenant_isolation ON sessions USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);
ALTER POLICY tenant_isolation ON patients USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);
ALTER POLICY tenant_isolation ON doctor_working_hours USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);
ALTER POLICY tenant_isolation ON doctor_leave USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);
ALTER POLICY tenant_isolation ON appointments USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);
ALTER POLICY tenant_isolation ON queue_entries USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);
ALTER POLICY tenant_isolation ON consents USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);
ALTER POLICY tenant_isolation ON patient_merges USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);
