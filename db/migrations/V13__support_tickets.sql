-- Support tickets (NB-265, SSA-08): raised by tenant owners/staff/doctors, or
-- by the system itself once an alert source exists (NB-263/264 aren't built
-- yet, so 'system' is a supported source with no caller wired in today).
-- Lives in master schema — support engineers triage across every tenant, so
-- this is platform-side like master.operators (V7): no RLS, access is
-- support_tickets:view (NB-257's matrix), not tenant isolation.
CREATE TABLE master.support_tickets (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           uuid NOT NULL REFERENCES tenants(id),
  source              text NOT NULL CHECK (source IN ('staff', 'system')),
  raised_by_staff_id  uuid REFERENCES staff(id),  -- null when source='system'
  raised_by_name      text NOT NULL,               -- snapshot: survives the staff row changing later
  raised_by_email     citext,
  raised_by_role      text NOT NULL,               -- 'Owner' / the staff member's role name / 'System'
  subject             text NOT NULL,
  description         text NOT NULL,
  priority            text NOT NULL DEFAULT 'normal' CHECK (priority IN ('low', 'normal', 'high', 'urgent')),
  status              text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'in_progress', 'resolved', 'closed')),
  sla_due_at          timestamptz NOT NULL,
  resolved_at         timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  CHECK (source = 'staff' OR raised_by_staff_id IS NULL)
);
CREATE TRIGGER trg_support_tickets_updated_at BEFORE UPDATE ON master.support_tickets
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE INDEX idx_support_tickets_tenant ON master.support_tickets (tenant_id);
-- Backs the console's breach-first ordering (open/in_progress tickets past sla_due_at sort to the top).
CREATE INDEX idx_support_tickets_open_sla ON master.support_tickets (sla_due_at)
  WHERE status IN ('open', 'in_progress');
