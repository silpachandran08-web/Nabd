-- ACC-039: 5-minute soft holds. Core's EXCLUDE constraint stays the final arbiter. The unique key is total (a partial
-- index cannot use now()); an expired row is taken over by the next holder (SlotHolds) or removed by the sweeper (V3).
CREATE TABLE booking_holds (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    doctor_id       uuid        NOT NULL,
    slot_start      timestamptz NOT NULL,
    conversation_id uuid        NOT NULL,
    expires_at      timestamptz NOT NULL,
    UNIQUE (tenant_id, doctor_id, slot_start)
);
CREATE INDEX booking_holds_expires_idx ON booking_holds (expires_at);

ALTER TABLE booking_holds ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_holds FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON booking_holds
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
