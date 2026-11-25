-- PLT-061 phase 1: the Control Plane's own database. It holds operator-plane data only: no patient, no clinical row
-- (classification.yaml next to the pom tags every column; NoPhiSchemaScanTest fails on an untagged or a PHI column).
--
-- V2 is GENERATED from Core's migrated `master` schema (same table and column names, so the platform code keeps its SQL
-- when it moves here). This file holds what is new: the schema, the extension V2's column types need, and tenant_directory.
CREATE EXTENSION IF NOT EXISTS citext;
CREATE SCHEMA master;

-- One row per clinic (tenant) of every cell: where it lives and whether it may be used. Ids only: the clinic's and the
-- owner's names and contacts stay in their cell. Fed by Core's events (TenantFeed) and, once, by the cut-over job.
-- status has no CHECK: a new lifecycle state is an allowed addition to the v1 event and must not dead-letter the feed.
CREATE TABLE tenant_directory (
  tenant_id       uuid PRIMARY KEY,
  brand_id        uuid,
  owner_id        uuid,
  region_code     text,
  cell            text,
  status          text NOT NULL DEFAULT 'provisioning',
  -- data.version of the last status event applied (monotonic per tenant); 0 = never fed by an event
  status_version  bigint NOT NULL DEFAULT 0,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);
