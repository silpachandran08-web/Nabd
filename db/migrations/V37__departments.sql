-- NB: departments as a first-class per-tenant entity, each with its own configurable visit
-- pipeline (currently just "requires vitals or not") and an owner-designed graph of which
-- departments can transfer a patient to which. "Transfer" (not "referral") throughout — source
-- already has an allowed value 'referral' meaning "how this patient found the clinic" (an
-- acquisition-channel tag, V24), an unrelated concept this deliberately avoids colliding with.

CREATE TABLE departments (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  name            text NOT NULL,
  requires_vitals boolean NOT NULL DEFAULT true,
  is_default      boolean NOT NULL DEFAULT false,
  active          boolean NOT NULL DEFAULT true,
  display_order   int NOT NULL DEFAULT 0,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, name)
);
CREATE TRIGGER trg_departments_updated_at BEFORE UPDATE ON departments
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE departments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON departments
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Exactly one fallback department per tenant — the check-in target when a doctor has no
-- department assigned yet (see QueueRepository.findCheckInDepartment), and the backfill target
-- for every already-provisioned tenant below.
CREATE UNIQUE INDEX uq_departments_default ON departments (tenant_id) WHERE is_default;

-- The owner-designed "which department can transfer a patient to which" graph.
CREATE TABLE department_transfers (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           uuid NOT NULL REFERENCES tenants(id),
  from_department_id  uuid NOT NULL REFERENCES departments(id),
  to_department_id    uuid NOT NULL REFERENCES departments(id),
  created_at          timestamptz NOT NULL DEFAULT now(),
  CHECK (from_department_id <> to_department_id),
  UNIQUE (tenant_id, from_department_id, to_department_id)
);

ALTER TABLE department_transfers ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON department_transfers
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Nullable: not every staff member is clinical/department-scoped, and there's no way to infer
-- which department an already-existing doctor belongs to — owners assign this going forward.
ALTER TABLE staff ADD COLUMN department_id uuid REFERENCES departments(id);

ALTER TABLE queue_entries ADD COLUMN department_id uuid REFERENCES departments(id);
-- Set only for a leg opened by a transfer (NULL for the first leg of every visit) — points back
-- to the leg it was transferred out of.
ALTER TABLE queue_entries ADD COLUMN parent_queue_entry_id uuid REFERENCES queue_entries(id);

ALTER TABLE queue_entries DROP CONSTRAINT queue_entries_status_check;
ALTER TABLE queue_entries ADD CONSTRAINT queue_entries_status_check CHECK (status IN
  ('checked_in', 'waiting', 'vitals_pending', 'vitals_done', 'in_consult', 'checkout_pending',
   'completed', 'no_show', 'transferred_out'));

ALTER TABLE queue_entries DROP CONSTRAINT queue_entries_source_check;
ALTER TABLE queue_entries ADD CONSTRAINT queue_entries_source_check CHECK (source IN
  ('walk_in', 'referral', 'online', 'social_media', 'returning', 'other', 'internal_transfer'));

-- ── Backward-compat backfill: every already-provisioned tenant gets a default department, and
-- every existing queue_entries row is backfilled onto it, before department_id becomes NOT NULL.
-- Nothing existing breaks the moment this ships, even for a tenant whose owner never touches the
-- new setup step. ──
INSERT INTO departments (tenant_id, name, requires_vitals, is_default)
SELECT id, 'General', true, true FROM tenants
ON CONFLICT (tenant_id, name) DO NOTHING;

UPDATE queue_entries q SET department_id = d.id
FROM departments d
WHERE d.tenant_id = q.tenant_id AND d.is_default AND q.department_id IS NULL;

ALTER TABLE queue_entries ALTER COLUMN department_id SET NOT NULL;

CREATE INDEX idx_queue_entries_department ON queue_entries (tenant_id, department_id, queue_date);
CREATE INDEX idx_queue_entries_parent ON queue_entries (parent_queue_entry_id) WHERE parent_queue_entry_id IS NOT NULL;

-- ── Setup wizard: add "departments" as a resumable step, right after "doctors" ──
ALTER TABLE clinic_setup_progress DROP CONSTRAINT clinic_setup_progress_step_check;
ALTER TABLE clinic_setup_progress ADD CONSTRAINT clinic_setup_progress_step_check CHECK (step IN (
  'welcome', 'profile', 'tax', 'doctors', 'departments', 'schedule', 'charges', 'pharmacy',
  'whatsapp', 'go_live'
));
