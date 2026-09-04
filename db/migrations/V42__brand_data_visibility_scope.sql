-- NB-359 (Arogya Fabric restructuring, Phase 3): activates the app.accessible_tenant_ids GUC V6
-- scaffolded on every tenant-scoped RLS policy but never populated. A brand now declares how far a
-- session inside one of its facilities can see: FACILITY (default -- today's exact behaviour, a
-- session sees only its own clinic), BRAND (every facility under the same brand), or TENANT (every
-- facility under every brand of the same owner -- the doc's widest, owner-level scope).
ALTER TABLE brands ADD COLUMN data_visibility_scope text NOT NULL DEFAULT 'FACILITY'
  CHECK (data_visibility_scope IN ('TENANT', 'BRAND', 'FACILITY'));
