SET lock_timeout = '5s';
-- ACC-015: one row per queue transition (written in the same transaction as the status CAS), doctor sessions with pauses.
-- Ids, statuses and times only: no PHI. Events are kept 13 months (archive job is a separate story).
CREATE TABLE queue_entry_events (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  queue_entry_id uuid NOT NULL,
  encounter_id   uuid NOT NULL,
  doctor_id      uuid NOT NULL,
  clinic_date    date NOT NULL,
  from_status    text,
  to_status      text NOT NULL,
  kind           text NOT NULL DEFAULT 'transition' CHECK (kind IN ('transition','extend','reassign','priority')),
  minutes        smallint CHECK (minutes IS NULL OR minutes IN (5,10,15)),
  occurred_at    timestamptz NOT NULL,
  actor_id       uuid,
  source         text NOT NULL CHECK (source IN ('desk','doctor','system','patient','backfill'))
);
CREATE INDEX idx_queue_entry_events_doctor_day ON queue_entry_events (tenant_id, doctor_id, clinic_date, occurred_at);
CREATE INDEX idx_queue_entry_events_entry ON queue_entry_events (queue_entry_id);
-- the backfill is idempotent on this key (live transitions legitimately repeat a status, backfill rows never do)
CREATE UNIQUE INDEX uq_queue_entry_events_backfill ON queue_entry_events (queue_entry_id, kind, to_status) WHERE source = 'backfill';

CREATE TABLE doctor_sessions (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        uuid NOT NULL,
  doctor_id        uuid NOT NULL,
  clinic_date      date NOT NULL,
  working_hours_id uuid,
  state            text NOT NULL CHECK (state IN ('in','paused','out')),
  started_at       timestamptz NOT NULL,
  ended_at         timestamptz
);
CREATE UNIQUE INDEX uq_doctor_sessions_open ON doctor_sessions (tenant_id, doctor_id, clinic_date) WHERE state <> 'out';
CREATE INDEX idx_doctor_sessions_day ON doctor_sessions (tenant_id, doctor_id, clinic_date, started_at DESC);

CREATE TABLE doctor_session_pauses (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL,
  session_id  uuid NOT NULL REFERENCES doctor_sessions(id),
  paused_at   timestamptz NOT NULL,
  resumed_at  timestamptz,
  reason_code text
);
CREATE INDEX idx_doctor_session_pauses_session ON doctor_session_pauses (session_id);

SELECT enforce_tenant_rls('queue_entry_events');
SELECT enforce_tenant_rls('doctor_sessions');
SELECT enforce_tenant_rls('doctor_session_pauses');
