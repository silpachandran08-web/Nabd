-- V37 added the "departments" module and granted it to every NEWLY-provisioned tenant's built-in
-- Owner role (ProvisioningStepRunner.seedMasters() writes a fresh grants snapshot each time), but
-- every already-provisioned tenant's Owner role is a JSONB snapshot taken before that module
-- existed, and built-in roles can't be edited through the API (RoleService.update() rejects it
-- outright) — so those owners would otherwise be permanently locked out of the Departments screen
-- with no self-service way to fix it. Backfill it directly, matching V37's own
-- already-provisioned-tenants-must-not-break backfill for departments/queue_entries.
UPDATE roles
SET grants = grants || '[{"module":"departments","view":true,"create":true,"edit":true,"delete":true,"approve":true,"refundDiscount":true,"export":true}]'::jsonb
WHERE built_in = true
  AND NOT EXISTS (
    SELECT 1 FROM jsonb_array_elements(grants) g WHERE g->>'module' = 'departments'
  );
