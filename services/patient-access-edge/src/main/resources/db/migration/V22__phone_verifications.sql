-- COM-041: a changed or recycled WhatsApp number must re-verify (OTP + date of birth) before records or family names are served.
-- Keyed by the hashed number like conversations; no number, no name, no date of birth. A phone without a row has had no signal.
-- wa_id_hash is the SHA-256 of Meta's WhatsApp user id as the Comms Gateway relays it. last_attempt_id makes a failed attempt
-- count once even when the engine re-runs the step after a version conflict.
CREATE TABLE phone_verifications (
    tenant_id        uuid        NOT NULL,
    phone_hash       text        NOT NULL,
    state            text        NOT NULL DEFAULT 'verified' CHECK (state IN ('verified', 'needs_reverify', 'locked')),
    reason           text CHECK (reason IN ('changed_number', 'wa_id_changed', 'silence', 'desk_change')),
    wa_id_hash       text,
    last_verified_at timestamptz,
    last_seen_at     timestamptz NOT NULL DEFAULT now(),
    failed_attempts  integer     NOT NULL DEFAULT 0,
    last_attempt_id  text,
    locked_until     timestamptz,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, phone_hash),
    CHECK (state <> 'locked' OR locked_until IS NOT NULL)
);

ALTER TABLE phone_verifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE phone_verifications FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON phone_verifications
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'edge_app') THEN
        GRANT SELECT, INSERT, UPDATE ON phone_verifications TO edge_app;
    END IF;
END $$;
