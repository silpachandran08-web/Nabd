# Initial database setup (once per database)

The API refuses to start (`RlsSelfCheck`) if its login is a superuser, has `BYPASSRLS`, owns tables, or if a tenant table lacks
`FORCE ROW LEVEL SECURITY`. So the database needs the OPS-003 role split before the first deploy:

| Role | Used by | Rights |
|---|---|---|
| `nabd_migrator` | the migrate job only | owns every table and function, runs Flyway, `NOBYPASSRLS` |
| `nabd_app` | the API | DML only, owns nothing, `NOBYPASSRLS` |
| `nabd_reports` | read-only reports | `SELECT`, 60 s statements |
| `nabd_definer` | nobody logs in | `BYPASSRLS`, owns the `SECURITY DEFINER` functions |

Never run the API as the provider's default owner login (e.g. Neon's `neondb_owner`).

## Steps (Neon, tested up to step 3 on 2026-10-19; steps 4 and 5 not yet confirmed)

Run steps 1 and 2 in the Neon SQL editor (or `psql`) as the default owner login. Passwords come from the secret manager, never from git.

1. **Create the roles.** `infra/db/bootstrap-roles.sql` (needs `psql` variables):
   ```
   psql -v ON_ERROR_STOP=1 -v migrator_pw="$MIGRATOR_PW" -v app_pw="$APP_PW" -v reports_pw="$REPORTS_PW" \
        -d "$DB_URL" -f infra/db/bootstrap-roles.sql
   ```
   Safe to re-run (it also rotates the passwords).
2. **Grant the owner login the right to hand objects over, then transfer ownership**, only if the tables were created before the
   split (by the old owner login). `infra/db/transfer-ownership.sql` does the grants, the transfer and the cleanup in one file:
   ```
   psql -v ON_ERROR_STOP=1 -d "$DB_URL" -f infra/db/transfer-ownership.sql
   ```
   It is a no-op on a new, empty database. Safe to re-run. Neon's owner is not a superuser, which is why the file first runs
   `GRANT nabd_migrator TO CURRENT_USER` (and `REVOKE`s it at the end) and grants `CREATE` on the `public` and `master` schemas.
3. **Check nothing is left under the old owner** (both queries must return no rows):
   ```sql
   SELECT schemaname, tablename, tableowner FROM pg_tables
    WHERE schemaname IN ('public','master') AND tableowner <> 'nabd_migrator';
   SELECT schemaname, sequencename, sequenceowner FROM pg_sequences
    WHERE schemaname IN ('public','master') AND sequenceowner <> 'nabd_migrator';
   ```
4. **Run the migrate job as `nabd_migrator`.** It applies the pending migrations, including V210 (grants to `nabd_app`, `FORCE ROW LEVEL SECURITY`).
   With the image: `docker build --target migrate -t nabd-migrate .`, then run it with
   `MIGRATE_DB_URL` (JDBC URL, e.g. `jdbc:postgresql://<host>/<db>?sslmode=require`), `MIGRATE_DB_USER=nabd_migrator`, `MIGRATE_DB_PASSWORD`.
   Without Docker, from a built jar (`mvn -f backend/pom.xml package -DskipTests`) with the same three variables set and
   `MIGRATE_LOCATIONS=filesystem:db/migrations`:
   ```
   java -cp backend/target/hms-backend-0.1.0.jar -Dloader.main=com.nabd.hms.common.MigrateMain \
        org.springframework.boot.loader.launch.PropertiesLauncher
   ```
   Run it again on every release, **before** the API rolls out.
5. **Point the API at `nabd_app`** (Render: Environment): `DB_USER=nabd_app`, `DB_PASSWORD=<app_pw>`, `DB_URL` unchanged, plus
   `APP_CRYPTO_KEY` (no default, boot fails without it). Remove every `neondb_owner` credential from the API service.
   PLT-044: also set `APP_CRYPTO_KEYRING=1:<the same base64 as APP_CRYPTO_KEY>` and `APP_CRYPTO_ACTIVE_KID=1` (the prod
   profile refuses to boot without a keyring; keeping APP_CRYPTO_KEY lets pre-PLT-044 values keep decrypting). To migrate old
   values set `APP_CRYPTO_REENCRYPT_ENABLED=true` until the log line `crypto re-encrypt verify` shows `failed=0 legacy=0` for
   every column. Rotation: add `2:<new key>` to the keyring, set `APP_CRYPTO_ACTIVE_KID=2`, run the job again; remove a kid
   (and finally APP_CRYPTO_KEY) only after a clean verify.
   Redeploy. A clean start logs no `RLS self-check failed`.

## Errors seen and what they mean

| Error | Cause | Fix |
|---|---|---|
| `RLS self-check failed ... superuser or has BYPASSRLS ... owns tables` | API runs as the default owner login | steps 1 to 5 |
| `must be able to SET ROLE "nabd_migrator"` | owner login is not a superuser | `GRANT nabd_migrator TO CURRENT_USER;` (in the transfer file) |
| `permission denied for schema public` / `master` | new owner lacks `CREATE` on the schema | `GRANT USAGE, CREATE ON SCHEMA <s> TO nabd_migrator, nabd_definer;` (in the transfer file) |
| `cannot change owner of sequence "..._id_seq"` | identity/serial sequences follow their table | already skipped by the transfer file |

## Not covered here

Rotating the passwords later (re-run step 1 with new values, then update the secrets), `nabd_reports` wiring, and the
backups/restore drill. See `docs/ops/ci.md` for the pipeline and `docs/ops/staging-in.md` for the staging cluster.
