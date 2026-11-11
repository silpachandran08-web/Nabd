-- OPS-050 / ACC-045 exit metric whatsapp_booking: median seconds from a conversation entering the booking menu to DONE,
-- per clinic (target < 60 s). Run against the EDGE database (funnel_steps, Edge V20; states and times only, no PHI).
-- Output: tenant_id,value,n.
WITH runs AS (
  SELECT tenant_id, conversation_id,
         min(at) FILTER (WHERE to_state = 'MENU') AS started,
         min(at) FILTER (WHERE to_state = 'DONE') AS booked
  FROM funnel_steps
  WHERE tenant_id = :'tenant'::uuid AND at >= :'from'::date AND at < :'to'::date + 1
  GROUP BY tenant_id, conversation_id
)
SELECT tenant_id, round(percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM booked - started))::numeric, 0) AS value,
       count(*) AS n
FROM runs
WHERE started IS NOT NULL AND booked > started
GROUP BY tenant_id;
