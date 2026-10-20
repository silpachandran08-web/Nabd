-- OPS-015: CloudEvents attributes on the existing transactional outbox (ADR-03: outbox_events stays the only publish
-- path). Legacy rows (whatsapp.send, otp ...) keep ce_type NULL; event_type stays the dispatcher's routing key and
-- equals ce_type for domain events. Additive only: nullable columns, no rewrite (schema_version default is a constant).
SET lock_timeout = '5s';

ALTER TABLE outbox_events
  ADD COLUMN ce_type text,
  ADD COLUMN ce_source text,
  ADD COLUMN aggregate_id uuid,
  ADD COLUMN trace_parent text,
  ADD COLUMN schema_version int DEFAULT 1;

-- data.version = this row's id: a later change of the same aggregate waits on that aggregate's row lock and so
-- draws a higher identity value, which makes it a monotonic per-aggregate version for consumers to drop stale events.
-- The identity default is already assigned when a BEFORE ROW trigger runs.
CREATE FUNCTION outbox_ce_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.ce_type IS NOT NULL THEN
    NEW.payload := NEW.payload || jsonb_build_object('version', NEW.id);
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_outbox_ce_version BEFORE INSERT ON outbox_events
  FOR EACH ROW EXECUTE FUNCTION outbox_ce_version();

-- The one rendering of the CloudEvents 1.0 envelope (docs/architecture/events.md), for the relay (OPS-016) and the
-- contract tests. security_invoker: the caller's RLS on outbox_events applies, so it never widens tenant visibility.
CREATE VIEW outbox_cloudevents WITH (security_invoker = true) AS
SELECT o.id, o.tenant_id, o.status, o.created_at,
       jsonb_strip_nulls(jsonb_build_object(
         'specversion', '1.0',
         'id', o.id::text,
         'source', o.ce_source,
         'type', o.ce_type,
         'time', to_char(o.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
         'subject', o.aggregate_id::text,
         'tenantid', o.tenant_id::text,
         'region', lower(t.region),
         'traceparent', o.trace_parent,
         'dataschema', 'urn:nabd:event-schema:' || o.ce_type,
         'datacontenttype', 'application/json'))
       || jsonb_build_object('data', o.payload) AS envelope
FROM outbox_events o
JOIN tenants t ON t.id = o.tenant_id
WHERE o.ce_type IS NOT NULL;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT SELECT ON outbox_cloudevents TO nabd_app;
  END IF;
END $$;
