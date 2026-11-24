SET lock_timeout = '5s';
-- PHL-042: reference ranges and critical limits per lab test, by sex and age band, in the test's own unit (lab_tests.unit).
-- A range set is versioned: saving writes every band again under version + 1 and nothing here is ever updated or deleted,
-- so a result flagged against version n can always show the band it was flagged against.
-- Age band: age_min_days is included, age_max_days is not (NULL = no upper age), so 0-28 and 28-365 meet without a gap.
-- No ranges are seeded: they are clinical content the lab enters (lab:validate).
CREATE TABLE lab_reference_ranges (
  id             uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id      uuid NOT NULL REFERENCES tenants (id),
  test_id        uuid NOT NULL REFERENCES lab_tests (id),
  version        int NOT NULL CHECK (version > 0),
  sex            text NOT NULL CHECK (sex IN ('M','F','any')),
  age_min_days   int NOT NULL CHECK (age_min_days >= 0),
  age_max_days   int CHECK (age_max_days > age_min_days),
  low            numeric,
  high           numeric,
  critical_low   numeric,
  critical_high  numeric,
  text_range     text CHECK (length(text_range) <= 200),
  effective_from timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_lab_reference_ranges_test ON lab_reference_ranges (tenant_id, test_id, version);
SELECT enforce_tenant_rls('lab_reference_ranges');
