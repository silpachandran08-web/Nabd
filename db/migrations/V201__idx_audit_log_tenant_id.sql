-- OPS-001 (DP-25): audit_log_chain() head lookup (WHERE tenant_id = ? ORDER BY id DESC LIMIT 1 FOR UPDATE) seq-scanned 3M rows.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_audit_log_tenant_id ON audit_log (tenant_id, id DESC);
