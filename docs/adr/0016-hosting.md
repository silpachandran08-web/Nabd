# ADR 0016: Hosting for PHI cells (IN now, AE / SA / QA next)

- **Status: PROPOSED. Not decided.** DEC-04 stays open until the product owner / CTO picks an option and signs below (due 27 Nov 2026).
- Story: OPS-052 (T-062). Related: ADR-09, ADR-15, DEC-04, DEC-19 (Redis-compatible store), DEC-21 (container platform), DEC-27 (KSA sovereign cloud), DEC-36 (contract RPO/RTO), DEC-40.
- Author: platform engineering (OPS-052). Reviewers needed: CTO (decision), `healthcare-compliance` (residency, CERT-In, ADHICS, NCA CCC), `security-privacy` (KMS, OIDC).

## 1. Problem

Today the system runs on **Render** (API, `/v1`), **Vercel** (frontend) and **Neon Postgres** (database), see `docs/ops/initial-setup.md`.
None of these lets us pin every PHI path (compute, database, backups, logs) to India, and none has a UAE, Saudi or Qatar region.
PHI therefore has to move to a provider with in-country regions. Vercel can stay for static and public assets only (no PHI, no API proxying of PHI).

## 2. Criteria (from DEC-04 / OPS-052)

| # | Criterion | Why |
|---|---|---|
| C1 | In-country regions in IN, AE, SA, QA (ideally two per country for DR) | Residency (DPDP, ADHICS, NCA CCC, Qatar MoPH); DR copies must stay in-country (OPS-054) |
| C2 | Managed Postgres with PITR (35 days), Multi-AZ, private endpoint | We are on Postgres + RLS; OPS-054 RPO 5 min / RTO 1 h |
| C3 | Managed Redis-compatible store | DEC-19 |
| C4 | KMS with customer-managed keys, envelope encryption, per-tenant DEKs | Data-class root keys now, per-tenant DEKs later |
| C5 | Container platform (managed Kubernetes or equivalent) | DEC-21; staging-in manifests already assume Kubernetes (OPS-053) |
| C6 | Logs kept in India for 180 days (CERT-In directions) and equivalent local rules elsewhere | Compliance |
| C7 | CI federation (OIDC) so no long-lived cloud keys exist | Security |
| C8 | Cost (run rate for one small cell + DR) | Budget |
| C9 | Healthcare / government attestations per country | Procurement; to be confirmed by compliance |

## 3. Options per country

Region names below are as known to the author on 2026-10-19. **Every cell marked "verify" must be confirmed against the provider's
current region list and compliance pages before signing.** No prices are quoted here: cost (C8) needs real quotes for the same
sizing (2 vCPU / 8 GB Multi-AZ Postgres, 3-node cluster, 1 TB object storage, DR copies).

### India (needed now)

| Option | C1 regions | C2 Postgres PITR | C3 Redis | C4 KMS | C5 containers | Notes |
|---|---|---|---|---|---|---|
| **A. AWS** | Mumbai `ap-south-1` + Hyderabad `ap-south-2` | RDS for PostgreSQL, PITR up to 35 d, cross-region automated-backup replication | ElastiCache / MemoryDB | KMS, multi-region keys | EKS | Matches the "Mumbai + Hyderabad" recommendation directly. Reference implementation in `infra/terraform/modules/cell/aws`. |
| **B. Microsoft Azure** | Central India (Pune), South India (Chennai), West India (Mumbai); Hyderabad region: verify | Flexible Server, PITR up to 35 d, geo-redundant backup to the paired region | Azure Cache for Redis | Key Vault / Managed HSM | AKS | Paired-region rules decide where geo-backups land: verify the pair stays in India. |
| **C. Google Cloud** | Mumbai `asia-south1` + Delhi `asia-south2` | Cloud SQL / AlloyDB, PITR (retention limits: verify) | Memorystore | Cloud KMS | GKE | No Hyderabad; DR would be Delhi. |
| D. Oracle Cloud | Mumbai + Hyderabad | OCI Database with PostgreSQL (PITR: verify) | OCI Cache | Vault | OKE | Smaller managed-Postgres track record: verify. |

### UAE (shortlist by month 8)

| Option | C1 | Notes |
|---|---|---|
| AWS | `me-central-1` (single region) | DR = second AZ set in the same region; cross-region DR would leave the country. ADHICS status: verify. |
| Azure | UAE North (Dubai) + UAE Central (Abu Dhabi) | Two regions in-country. ADHICS status: verify. |
| Sovereign / local (e.g. Core42 / G42 cloud) | verify | Often required for Abu Dhabi DoH workloads: verify with compliance. |

### Saudi Arabia (shortlist by month 8; DEC-27 sovereign cloud)

| Option | C1 | Notes |
|---|---|---|
| Google Cloud | Dammam `me-central2` | NCA CCC classification: verify. |
| Oracle Cloud | Jeddah + Riyadh | NCA CCC: verify. |
| Sovereign (e.g. SCCC / STC cloud) | verify | DEC-27: a sovereign choice only swaps the SA instantiation (`cells/sa`), not the module. |
| AWS / Azure | announced Saudi regions: verify availability | |

### Qatar

| Option | C1 | Notes |
|---|---|---|
| Google Cloud | Doha `me-central1` | verify |
| Azure | Qatar Central (Doha) | verify |

## 4. Proposed direction (for the decision-maker, not decided)

1. India: **Option A (AWS Mumbai primary, Hyderabad DR)** is the closest fit to C1 and C2 as written in DEC-04 (two Indian regions,
   35-day PITR with cross-region backup copies, both in India). Option B is the main alternative if the Hyderabad Azure region is
   confirmed or if a Pune/Chennai pair is acceptable for DR.
2. Do **not** standardise every country on one hyperscaler yet: UAE and KSA may need local/sovereign providers (ADHICS, NCA CCC, DEC-27).
   The Terraform layout is built so that this is a per-country choice.
3. Vercel: static and public assets only. Render and Neon: no PHI after the IN cell is live; current data migrates during cut-over
   (separate story).

## 5. How the code keeps DEC-04 open

- `infra/terraform/modules/cell` is provider-neutral: inputs (cell, country, region, DR region, data classes), tag contract
  (`cell`, `service`, `data_class` on every resource), residency check, and outputs. It contains no provider resources.
- Provider code lives only in submodules (`modules/cell/aws` today). Choosing Azure or GCP means adding `modules/cell/azure|gcp`
  with the same outputs and one `module` block in `modules/cell/main.tf`; the country instantiations (`cells/in`, later `cells/ae`...)
  only change `cloud` and `region`.
- A plan-only test (`modules/cell/tests/second_region.tftest.hcl`) instantiates the module for a second country with mocked providers.

## 6. Consequences

- New stack items needing their own approvals: Terraform/OpenTofu (added by OPS-052), Kubernetes (DEC-21), a Redis-compatible store (DEC-19).
- An AWS choice makes `bootstrap/aws` (state + OIDC) the account baseline; another choice needs an equivalent bootstrap.
- Cost (C8) and attestations (C9) are unknown until quotes and compliance review are in.

## 7. Decision and sign-off (empty until decided)

| | |
|---|---|
| Chosen option (IN) | _not decided_ |
| Chosen options (AE / SA / QA) or date to decide | _not decided_ |
| Signed by CTO | _pending (Needs external action)_ |
| Date | |
| DEC-04 closed | No |
