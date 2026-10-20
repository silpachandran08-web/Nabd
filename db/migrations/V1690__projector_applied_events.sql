SET lock_timeout = '5s';
-- OPS-017: applied-events ledger of the DB-projection consumer groups (docs/architecture/events.md section 7).
-- One row per (consumer group, CloudEvents id): the consumer inserts it in the same tenant transaction as its
-- projection, so a redelivered event is a no-op and a rolled-back handler leaves no row. version and subject feed
-- the version gate (apply only if data.version > the highest version applied for that subject).
-- shortcut: rows are never purged (one small row per event per projector); add a ShedLock purge keeping each
-- subject's latest row once the ledger passes a few million rows.
CREATE TABLE projector_applied_events (
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  consumer_group text NOT NULL CHECK (consumer_group ~ '^[a-z][a-z0-9-]{1,40}$'),
  event_id       text NOT NULL CHECK (length(event_id) <= 64),
  subject        uuid,
  version        bigint NOT NULL,
  applied_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, consumer_group, event_id)
);
CREATE INDEX idx_projector_applied_subject ON projector_applied_events (tenant_id, consumer_group, subject, version)
  WHERE subject IS NOT NULL;

SELECT enforce_tenant_rls('projector_applied_events');
