SET lock_timeout = '5s';
-- ADM-003: self-serve trial sign-ups. Platform table (no tenant yet), so no tenant RLS; nabd_app reaches it through the
-- public trial endpoints only. The mobile is stored as a salted-by-secret hash; the raw number lives only in the OTP service.
CREATE TABLE master.trial_signups (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  mobile_hash   text NOT NULL,
  ip_hash       text NOT NULL,
  market        text NOT NULL,
  region        text NOT NULL,
  clinic_name   text NOT NULL,
  specialty     text NOT NULL,
  otp_hash      text,
  otp_attempts  int  NOT NULL DEFAULT 0,
  status        text NOT NULL DEFAULT 'otp_sent' CHECK (status IN ('otp_sent','verified','provisioning','ready','expired')),
  job_id        uuid,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_trial_signups_mobile ON master.trial_signups (mobile_hash, created_at);
CREATE INDEX idx_trial_signups_ip ON master.trial_signups (ip_hash, created_at);
