SET lock_timeout = '5s';
-- ADM-043: the owner's daily WhatsApp summary. One settings row per clinic (facility = tenant): opt-in, the owner-role
-- recipients, 'close' (last working-hours end + 30 min, or the day close if earlier) or a fixed HH:mm, and whether a
-- chain owner wants one combined message for every clinic of the brand. last_sent_day makes the scheduler idempotent
-- per clinic day; per recipient the notification request's dedupe key (date + tenant + recipient) does the same.
CREATE TABLE daily_summary_settings (
  tenant_id     uuid PRIMARY KEY REFERENCES tenants(id),
  enabled       boolean NOT NULL DEFAULT false,
  combined      boolean NOT NULL DEFAULT false,
  recipients    uuid[] NOT NULL DEFAULT '{}',
  send_at       text NOT NULL DEFAULT 'close' CHECK (send_at = 'close' OR send_at ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
  last_sent_day date,
  updated_by    uuid REFERENCES staff(id),
  updated_at    timestamptz NOT NULL DEFAULT now()
);
SELECT enforce_tenant_rls('daily_summary_settings');
