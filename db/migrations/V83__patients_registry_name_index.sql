-- REC-040: the Patient Registry reads a tenant's patients ordered by (name, id) with a LIMIT and pages that
-- result. V3 only indexes (tenant_id, created_at, id), phone, mrn, dob and a trigram on name, none of which
-- serve that ORDER BY. Additive and idempotent; an index needs no RLS policy (patients keeps its V3/V6 policy).
CREATE INDEX IF NOT EXISTS idx_patients_tenant_name ON patients (tenant_id, name, id);
