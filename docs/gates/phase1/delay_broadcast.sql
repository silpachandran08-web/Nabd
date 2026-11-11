-- OPS-050 exit metric delay_broadcast: median seconds from a doctor delay being announced to the first patient message being
-- queued for it (target < 60 s). Source: doctor_delays -> delay_broadcast_recipients (ACC-030). Output: tenant_id,value,n.
-- shortcut: measures until the message is queued in Core, not until WhatsApp delivery (that time lives in the comms gateway);
-- add the gateway's delivered_at when the exit review asks for end-to-end.
WITH per_delay AS (
  SELECT d.tenant_id, d.id, extract(epoch FROM min(r.created_at) - d.announced_at) AS secs
  FROM doctor_delays d
  JOIN delay_broadcast_recipients r ON r.delay_id = d.id AND r.tenant_id = d.tenant_id
  WHERE d.tenant_id = :'tenant'::uuid AND d.announced_at >= :'from'::date AND d.announced_at < :'to'::date + 1
  GROUP BY d.tenant_id, d.id, d.announced_at
)
SELECT tenant_id, round(percentile_cont(0.5) WITHIN GROUP (ORDER BY secs)::numeric, 0) AS value, count(*) AS n
FROM per_delay
GROUP BY tenant_id;
