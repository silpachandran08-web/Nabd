-- NB-262: the fleet console lists tenants across the whole platform, not scoped to one tenant_id
-- like every other keyset-paginated list in this codebase — so the (created_at, id) ordering it
-- sorts by needs its own index rather than riding along on a tenant_id index like the others do.
CREATE INDEX idx_tenants_created_at ON tenants (created_at, id);
