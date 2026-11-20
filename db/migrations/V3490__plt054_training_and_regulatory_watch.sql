SET lock_timeout = '5s';
-- PLT-054 (T-319): security-training completions per staff member. One row per pass of a module; the newest row of a
-- module is the current one and a refresher is due one year after it (TrainingRules). A repeat on the same UTC day is the
-- same completion (the unique index makes POST /v1/training/completions idempotent). Non-PHI.
CREATE TABLE training_completions (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL REFERENCES tenants (id),
  staff_id     uuid NOT NULL REFERENCES staff (id),
  module_id    text NOT NULL CHECK (module_id ~ '^[a-z][a-z0-9-]{1,40}$'),
  score        int  NOT NULL CHECK (score BETWEEN 0 AND 100),
  completed_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_training_completions_day
  ON training_completions (tenant_id, staff_id, module_id, ((completed_at AT TIME ZONE 'UTC')::date));
SELECT enforce_tenant_rls('training_completions');

-- The same record for Nabd's own operators (first week after joining, then yearly). Platform data: no tenant, no RLS (V7).
CREATE TABLE master.operator_training_completions (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id  uuid NOT NULL REFERENCES master.operators (id),
  module_id    text NOT NULL CHECK (module_id ~ '^[a-z][a-z0-9-]{1,40}$'),
  score        int  NOT NULL CHECK (score BETWEEN 0 AND 100),
  completed_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_operator_training_completions_day
  ON master.operator_training_completions (operator_id, module_id, ((completed_at AT TIME ZONE 'UTC')::date));

-- PLT-054 (T-330, SSA-11): the DPO's regulatory-watch log. One row per regulatory change: where it applies (a regions
-- code or ALL), what it means for Nabd, and the tracker task (T-n) or decision (DEC-n) that carries the follow-up.
-- noticed_on = the day the change was published or first seen; recorded_at within 5 working days of it is the target.
-- Rows are evidence: corrections are a new row, nothing is edited (the app role has no DELETE on master, V210).
CREATE TABLE master.regulatory_watch_entries (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  source       text NOT NULL CHECK (source IN ('DPDP','NCNICC','ZATCA','TRAI_DLT','ABDM','NABIDH','META','OTHER')),
  market       text NOT NULL CHECK (market ~ '^[A-Z]{2,3}$'),
  title        text NOT NULL CHECK (length(title) BETWEEN 3 AND 200),
  impact_level text NOT NULL CHECK (impact_level IN ('none','low','medium','high')),
  impact       text NOT NULL CHECK (length(impact) BETWEEN 3 AND 2000),
  linked_ref   text NOT NULL CHECK (linked_ref ~ '^(T|DEC)-[0-9]{1,6}$'),
  noticed_on   date NOT NULL,
  recorded_at  timestamptz NOT NULL DEFAULT now(),
  recorded_by  uuid NOT NULL REFERENCES master.operators (id)
);
CREATE INDEX idx_regulatory_watch_noticed ON master.regulatory_watch_entries (noticed_on DESC);
