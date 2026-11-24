SET lock_timeout = '5s';
-- PHL-045: order of draw for sample containers. This is laboratory reference data marked NEEDS LAB REVIEW by clinic staff
-- before they release samples to the bench. Standard order prevents clotting and cross-contamination issues.
-- See ASM-02 and LB-02 in the requirements. This table is tenant-scoped but seeded with the same standard order for all.
CREATE TABLE lab_container_order (
  id              uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id       uuid NOT NULL REFERENCES tenants (id),
  container_name  text NOT NULL,
  draw_sequence   int NOT NULL CHECK (draw_sequence > 0),
  UNIQUE (tenant_id, container_name),
  UNIQUE (tenant_id, draw_sequence)
);
CREATE INDEX idx_lab_container_order_tenant ON lab_container_order (tenant_id, draw_sequence);
SELECT enforce_tenant_rls('lab_container_order');

-- Seed standard order for all existing tenants. The sequence follows CLSI standards: 1=citrate, 2=serum separator,
-- 3=heparin, 4=EDTA, 5=glucose, etc. This is a guideline; clinics can customize through the admin screen (not exposed yet).
-- NEEDS LAB REVIEW: clinic staff must review before releasing; this data is NOT finalized.
INSERT INTO lab_container_order (tenant_id, container_name, draw_sequence)
SELECT id, container_name, sequence
FROM tenants, (
  VALUES
    ('Citrate', 1),
    ('Serum Separator (SST)', 2),
    ('Heparin', 3),
    ('EDTA', 4),
    ('Glucose', 5),
    ('Plain', 6)
) AS std(container_name, sequence)
ON CONFLICT (tenant_id, container_name) DO NOTHING;
