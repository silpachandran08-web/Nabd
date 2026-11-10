-- OPS-050 exit metric eta_within10: share of visits whose ETA was within +/-10 min (target >= 80%).
-- Source: eta_accuracy_daily (ACC-019 rollup, one row per doctor per clinic day). Output: tenant_id,value,n (value 0..1, n = visits).
-- Run read-only per clinic (run.sh binds app.tenant_id; RLS is forced). Variables: :'tenant', :'from', :'to' (ISO dates).
SELECT tenant_id,
       round(sum(within10_pct * n) / nullif(sum(n), 0), 4) AS value,
       sum(n) AS n
FROM eta_accuracy_daily
WHERE tenant_id = :'tenant'::uuid AND clinic_date BETWEEN :'from'::date AND :'to'::date
GROUP BY tenant_id;
