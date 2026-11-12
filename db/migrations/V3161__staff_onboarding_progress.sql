SET lock_timeout = '5s';
-- ACC-035: first-run tours and the reception competency check, per staff member (replaces FEF-062's per-browser flag).
-- tour_key: 'tour.<role>' (completed_at = the tour was finished or skipped), 'task.<id>' (one of the ten competency tasks),
-- 'competency.reception' (competency_passed_at = all ten done). Non-PHI.
CREATE TABLE staff_onboarding_progress (
  tenant_id            uuid NOT NULL REFERENCES tenants (id),
  staff_id             uuid NOT NULL REFERENCES staff (id),
  tour_key             text NOT NULL CHECK (tour_key ~ '^[a-z]+\.[a-z_]{1,40}$'),
  completed_at         timestamptz,
  competency_passed_at timestamptz,
  PRIMARY KEY (tenant_id, staff_id, tour_key)
);
SELECT enforce_tenant_rls('staff_onboarding_progress');
