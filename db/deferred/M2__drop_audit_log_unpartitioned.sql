-- OPS-068 contract step (NOT a Flyway migration: this folder is not on the migration path).
-- Ship as the next db/migrations/V<n>__drop_audit_log_unpartitioned.sql no earlier than TWO RELEASES AFTER V3671 is in production
-- (or after the online conversion of docs/architecture/audit-partitioning.md section 10.2), once verify_audit_chain has passed for
-- every tenant on the partitioned table and a backup taken after the conversion has been restored and verified.
-- audit_log_unpartitioned is the pre-conversion copy kept for rollback; nothing reads it (no grant for nabd_app or nabd_reports).
-- The lint needs CONTRACT_REF=<the V3671 release> for DROP TABLE (MIG-007).
-- Also in the same PR: remove audit_log_unpartitioned from db/classification.yaml and docs/architecture/table-ownership.yaml.
SET lock_timeout = '5s';
DROP TABLE IF EXISTS audit_log_unpartitioned;
