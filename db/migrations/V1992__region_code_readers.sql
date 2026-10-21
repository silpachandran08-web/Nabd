-- OPS-061 switch step: the database readers of the old tenants.region (CloudEvents envelope view, outbox gauges) read
-- region_code. Envelope region is now the cell code of the tenant's region (in, sa, ae, qa), so stream names are ev:sa:... .
SET lock_timeout = '5s';

CREATE OR REPLACE VIEW outbox_cloudevents WITH (security_invoker = true) AS
SELECT o.id, o.tenant_id, o.status, o.created_at,
       jsonb_strip_nulls(jsonb_build_object(
         'specversion', '1.0',
         'id', o.id::text,
         'source', o.ce_source,
         'type', o.ce_type,
         'time', to_char(o.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
         'subject', o.aggregate_id::text,
         'tenantid', o.tenant_id::text,
         'region', lower(t.region_code),
         'traceparent', o.trace_parent,
         'dataschema', 'urn:nabd:event-schema:' || o.ce_type,
         'datacontenttype', 'application/json'))
       || jsonb_build_object('data', o.payload) AS envelope
FROM outbox_events o
JOIN tenants t ON t.id = o.tenant_id
WHERE o.ce_type IS NOT NULL;

CREATE OR REPLACE FUNCTION outbox_stats()
RETURNS TABLE (region text, pending bigint, dead bigint, oldest_pending_age_seconds double precision)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
  SELECT r.code::text,
         count(o.id) FILTER (WHERE o.status IN ('pending', 'processing')),
         count(o.id) FILTER (WHERE o.status = 'dead'),
         COALESCE(EXTRACT(EPOCH FROM now() - min(o.created_at) FILTER (WHERE o.status IN ('pending', 'processing'))), 0)::double precision
  FROM regions r
  LEFT JOIN tenants t ON t.region_code = r.code
  LEFT JOIN outbox_events o ON o.tenant_id = t.id AND o.status <> 'done'
  GROUP BY r.code
$$;

SELECT nabd_adopt_definer_functions();
