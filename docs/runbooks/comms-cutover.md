# Comms cutover (COM-008): per tenant, then the old tables go read-only, then they are dropped

Order, one tenant at a time. Nothing here is automatic; every step is an operator action.

1. Backfill (resumable, idempotent, safe to re-run): `TenantBackfill.run(tenantId)` (`services/comms-gateway`, `com.nabd.comms.store`)
   with a read-only Core credential and the gateway's field cipher. It copies templates, messages and inbound events and writes the
   row counts and status histograms of both sides into `backfill_markers`. Continue only when every marker is `done` (a `mismatch` is a stop).
2. Flip the flag in Core, in the tenant's context:
   `INSERT INTO comms_cutover_flags (tenant_id, remote) VALUES (:t, true) ON CONFLICT (tenant_id) DO UPDATE SET remote = true, updated_at = now();`
   From the next delivery on, `NotificationService.deliver` returns without sending; the gateway claims the request over
   `/internal/v1/notification-requests/{id}`. Rollback: set `remote = false` (requests not yet claimed are sent by Core again).
   Requests already claimed by the gateway are not re-sent by Core because their status leaves `requested`.
3. Consent snapshot: confirm `comms_consent_drift_total` is flat for 3 nightly reconciliations
   (`GET /internal/v1/consents:export?tenantId=...`).
4. After the LAST tenant is cut over: `UPDATE comms_cutover_flags SET legacy_readonly = true` for all tenants. Trigger
   `trg_comms_readonly` (V2200) then refuses INSERT/UPDATE on `whatsapp_messages`, `whatsapp_templates`, `whatsapp_inbound_events`
   for those tenants. Reads keep working for the one-release rollback window.
5. One release later: drop migration (a new Core `V<n>__drop_legacy_whatsapp_tables.sql`, scheduled for the release after the
   one that sets `legacy_readonly`; not written yet because it must not ship before step 4 has run in production).
   Needs external action: choosing the release and the production run of steps 1-4.
