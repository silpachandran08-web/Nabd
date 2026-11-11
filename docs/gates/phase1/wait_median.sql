-- OPS-050 exit metric wait_median: median minutes from a queue entry's first event (check-in / token) to its first
-- in_consult transition (target < 15 min). Source: queue_entry_events (ACC-015, ids and times only). Output: tenant_id,value,n.
WITH per_entry AS (
  SELECT tenant_id, queue_entry_id,
         min(occurred_at) AS joined_at,
         min(occurred_at) FILTER (WHERE to_status = 'in_consult' AND kind = 'transition') AS called_at
  FROM queue_entry_events
  WHERE tenant_id = :'tenant'::uuid AND clinic_date BETWEEN :'from'::date AND :'to'::date AND source <> 'backfill'
  GROUP BY tenant_id, queue_entry_id
)
SELECT tenant_id,
       round((percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM called_at - joined_at)) / 60)::numeric, 1) AS value,
       count(*) AS n
FROM per_entry
WHERE called_at IS NOT NULL
GROUP BY tenant_id;
