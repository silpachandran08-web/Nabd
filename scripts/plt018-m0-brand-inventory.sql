-- PLT-018 / CONS M0: every brand not on FACILITY scope, with its owner and facilities.
-- Read-only. Run as the DB owner against prod; send the result to the platform team, who contact each
-- owner before nabd.legacy-brand-visibility's default is ever changed in prod (this script does not contact anyone).
SELECT b.id AS brand_id, b.name AS brand_name, b.data_visibility_scope,
       o.id AS owner_id, o.name AS owner_name, o.email AS owner_email, o.status AS owner_status,
       count(t.id) AS facilities
FROM brands b
JOIN owners o ON o.id = b.owner_id
LEFT JOIN tenants t ON t.brand_id = b.id
WHERE b.data_visibility_scope <> 'FACILITY'
GROUP BY b.id, b.name, b.data_visibility_scope, o.id, o.name, o.email, o.status
ORDER BY b.data_visibility_scope, o.email, b.name;
