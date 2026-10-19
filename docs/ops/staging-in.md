# staging-in (OPS-053)

Production-shaped: 2 Core replicas, PgBouncer, real roles (`nabd_app`), synthetic PHI only. Manifest: `infra/staging-in/core.yaml`.

## How the criteria are met
- Two replicas, LB to ready pods only: `replicas: 2`, `readinessProbe`, Service without sticky sessions.
- No logout on restart: signing keys come from the shared `JWT_KEYS_FILE` secret (PLT-002), refresh tokens live in Postgres, so any pod accepts any session. `server.shutdown: graceful` plus `preStop sleep 10` and `maxUnavailable: 0` drain a pod before it dies.
- Scheduled jobs once: the outbox dispatcher claims rows with a DB lease, so two replicas do not double-process. ShedLock (OPS-013) is not in the tree; any new `@Scheduled` job needs it.
- Verification: `python3 infra/staging-in/check-staging-in.py` (manifest properties, CI-safe), `infra/staging-in/rolling-restart-test.sh` (live session, kills one pod), `rolling-deploy.k6.js` (error rate < 0.1%). Workflow: `.github/workflows/staging-in.yml` (manual).

## Needs external action
- Create the cluster/namespace, PgBouncer, LB, and secrets `core-secrets`, `core-jwt-keys`, plus repo secrets `STAGING_IN_*`; an ADR for the container platform (Kubernetes is not yet in the approved stack).
- Probe endpoints `/actuator/health/readiness|liveness` come from OPS-025.
- Running the full API suite and prod-config job against staging-in: the suite is Testcontainers-based and has no remote-target mode; add one and run it in CI once the environment exists.
- Executing any of the above runs (they could not run in this sandbox).
