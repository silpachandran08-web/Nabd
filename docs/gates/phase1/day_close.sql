-- OPS-050 exit criterion day_close: one row per closed clinic day; phase1_gate.py dayclose finds the longest run of consecutive
-- days with counted == expected (target >= 10 per clinic). Source: day_closes (FIN-005). Output: tenant_id,close_date,expected,counted.
SELECT tenant_id, close_date, expected_cash AS expected, counted_cash AS counted
FROM day_closes
WHERE tenant_id = :'tenant'::uuid AND status = 'closed' AND close_date BETWEEN :'from'::date AND :'to'::date
ORDER BY close_date;
