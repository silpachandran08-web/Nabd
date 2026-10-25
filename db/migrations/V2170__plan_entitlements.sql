-- ADM-034 + PLT-059: plan catalogue (versions, modules, limits, market, pricing basis), per-tenant feature overrides and
-- global kill switches. master schema = operator plane (no RLS, see rls-exemptions.txt); the app role gets
-- SELECT/INSERT/UPDATE by default privileges, so versions are appended, never deleted.
SET lock_timeout = '5s';

ALTER TABLE master.plans
  ADD COLUMN version int NOT NULL DEFAULT 1,
  ADD COLUMN market text,
  ADD COLUMN pricing_basis text NOT NULL DEFAULT 'per_clinic' CHECK (pricing_basis IN ('per_clinic','per_doctor')),
  ADD COLUMN branch_limit int CHECK (branch_limit > 0),
  ADD COLUMN visits_per_month int CHECK (visits_per_month > 0),
  ADD COLUMN on_trial_expiry text;

ALTER TABLE master.plans DROP CONSTRAINT plans_currency_check;
ALTER TABLE master.plans ADD CONSTRAINT plans_currency_check CHECK (currency IN ('INR','SAR','AED','QAR')) NOT VALID;
ALTER TABLE master.plans VALIDATE CONSTRAINT plans_currency_check;
ALTER TABLE master.subscriptions DROP CONSTRAINT subscriptions_currency_check;
ALTER TABLE master.subscriptions ADD CONSTRAINT subscriptions_currency_check CHECK (currency IN ('INR','SAR','AED','QAR')) NOT VALID;
ALTER TABLE master.subscriptions VALIDATE CONSTRAINT subscriptions_currency_check;

-- A subscription stays on the plan version it was sold; moving it is an explicit operator action.
ALTER TABLE master.subscriptions ADD COLUMN plan_version int NOT NULL DEFAULT 1;

-- One row per published version of a plan: the numbers a pinned subscription is held to.
CREATE TABLE master.plan_versions (
  plan_id            uuid NOT NULL REFERENCES master.plans(id),
  version            int  NOT NULL CHECK (version > 0),
  monthly_price_cents integer NOT NULL CHECK (monthly_price_cents >= 0),
  seat_limit         integer NOT NULL CHECK (seat_limit > 0),
  branch_limit       integer CHECK (branch_limit > 0),
  visits_per_month   integer CHECK (visits_per_month > 0),
  published_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (plan_id, version)
);
INSERT INTO master.plan_versions (plan_id, version, monthly_price_cents, seat_limit)
  SELECT id, 1, monthly_price_cents, seat_limit FROM master.plans;

-- Modules a plan version includes (closed list, validated in PlanService). A version with no rows is a legacy plan
-- that predates entitlements: it keeps every module (no tenant loses a feature by this migration).
CREATE TABLE master.plan_entitlements (
  plan_id      uuid NOT NULL REFERENCES master.plans(id),
  plan_version int  NOT NULL,
  module_code  text NOT NULL,
  limits       jsonb NOT NULL DEFAULT '{}'::jsonb,
  PRIMARY KEY (plan_id, plan_version, module_code),
  FOREIGN KEY (plan_id, plan_version) REFERENCES master.plan_versions(plan_id, version)
);

CREATE TABLE master.tenant_feature_overrides (
  tenant_id    uuid NOT NULL REFERENCES tenants(id),
  feature_code text NOT NULL,
  state        text NOT NULL CHECK (state IN ('on','off','kill')),
  reason       text NOT NULL,
  set_by       uuid,
  set_at       timestamptz NOT NULL DEFAULT now(),
  expires_at   timestamptz,
  PRIMARY KEY (tenant_id, feature_code)
);

CREATE TABLE master.kill_switches (
  feature_code text PRIMARY KEY,
  active       boolean NOT NULL,
  reason       text NOT NULL,
  set_by       uuid,
  set_at       timestamptz NOT NULL DEFAULT now()
);
