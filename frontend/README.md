# Nabd frontend (pnpm workspace + Turborepo)

FEF-012, doc 06 s5.2. Run everything from this folder (`frontend/`).

| Path | What |
|---|---|
| `apps/staff-web` | Clinic + platform console Next.js app (was `frontend/` before Sprint 12) |
| `packages/api-client` (`@nabd/api-client`) | The only code that calls `fetch`. OPS-035 writes the generated client to `src/generated/` |
| `packages/domain` | Framework-free domain logic (skeleton; FEF-013) |
| `packages/ui` | Design-system components. No fetching, no `@nabd/api-client` (lint fails) |
| `packages/tokens` | Design tokens (`--nb-*`) |

Owners: `packages/ui` and `packages/tokens` need a Frontend squad review (`.github/CODEOWNERS`).

## Commands
```bash
pnpm install                                   # Node 22, pnpm 10 (corepack enable)
pnpm turbo run build lint typecheck test       # whole pipeline; unchanged packages are cache hits
pnpm --filter staff-web dev                    # dev server on :3000 (copy apps/staff-web/.env.local.example to .env.local)
pnpm --filter staff-web run check:permissions  # permission rendering check
```
Inside `apps/staff-web`: `pnpm run test:unit`, `pnpm run typecheck` (`next typegen && tsc --noEmit`), `pnpm exec eslint .`, `pnpm exec next build`.
Browser scripts: `cd apps/staff-web && NODE_PATH=<playwright node_modules> APP=http://localhost:<port> node tests/e2e/<script>.mjs`.

## Lint boundaries (eslint-plugin-boundaries, rule `boundaries/dependencies`)
- `apps/staff-web/eslint.config.mjs`: a feature folder (`app/components/<name>`) imports only `packages/*`, shared app code and itself.
  Three existing cross-feature edges are a frozen baseline (FEF-013 moves them to `packages/domain`).
- `eslint.config.mjs` (this folder, used by `packages/*`): only `api-client` imports `@nabd/api-client` or calls `fetch`.
- Must-fail samples live in `lint-fixtures/` and are checked by `apps/staff-web/tests/unit/s12-fef012-monorepo.test.mjs`.

## Turborepo cache
Local cache in `.turbo/cache`; CI keeps it on the runner with `actions/cache` (no remote SaaS cache).
Unit tests also read repo files outside `frontend/` (`api/`, `.github/`, `perf/`, `renovate.json`); CI passes their git hashes in
`NABD_REPO_INPUTS` so a change there invalidates the `test` cache.
