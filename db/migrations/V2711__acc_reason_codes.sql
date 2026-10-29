SET lock_timeout = '5s';
-- ACC-007: one coded reason list for cancel / no_show / reschedule / override / refusal.
-- Platform reference table: no tenant_id, read-only for nabd_app. Tenants add entries in tenant_reason_codes.
CREATE TABLE reason_code_catalogue (
  kind          text    NOT NULL CHECK (kind IN ('cancel','no_show','reschedule','override','refusal')),
  code          text    NOT NULL CHECK (code ~ '^[a-z0-9_]{3,40}$'),
  market        text    NOT NULL CHECK (market IN ('IN','AE','SA','QA')),
  labels        jsonb   NOT NULL,
  requires_note boolean NOT NULL DEFAULT false,
  sort          int     NOT NULL,
  PRIMARY KEY (kind, code, market)
);

-- Seed: (kind, code) pairs x the four markets. Labels per code in EN/HI/KN/TE/AR (native review before go-live).
WITH labels(code, l) AS (VALUES
  ('schedule_clash',     '{"en":"Schedule clash","hi":"समय में टकराव","kn":"ವೇಳಾಪಟ್ಟಿ ಘರ್ಷಣೆ","te":"షెడ్యూల్ క్లాష్","ar":"تعارض في الموعد"}'::jsonb),
  ('illness',            '{"en":"Illness","hi":"बीमारी","kn":"ಅನಾರೋಗ್ಯ","te":"అనారోగ్యం","ar":"مرض"}'::jsonb),
  ('cost',               '{"en":"Cost","hi":"खर्च","kn":"ವೆಚ್ಚ","te":"ఖర్చు","ar":"التكلفة"}'::jsonb),
  ('transport',          '{"en":"Transport","hi":"आवागमन","kn":"ಸಾರಿಗೆ","te":"రవాణా","ar":"المواصلات"}'::jsonb),
  ('went_elsewhere',     '{"en":"Went elsewhere","hi":"कहीं और गए","kn":"ಬೇರೆಡೆ ಹೋದರು","te":"వేరే చోటికి వెళ్లారు","ar":"ذهب إلى مكان آخر"}'::jsonb),
  ('clinic_initiated',   '{"en":"Clinic-initiated","hi":"क्लिनिक की ओर से","kn":"ಕ್ಲಿನಿಕ್ ಕೋರಿಕೆ","te":"క్లినిక్ ద్వారా","ar":"بطلب من العيادة"}'::jsonb),
  ('doctor_unavailable', '{"en":"Doctor unavailable","hi":"डॉक्टर उपलब्ध नहीं","kn":"ವೈದ್ಯರು ಲಭ್ಯರಿಲ್ಲ","te":"డాక్టర్ అందుబాటులో లేరు","ar":"الطبيب غير متاح"}'::jsonb),
  ('rescheduled',        '{"en":"Rescheduled","hi":"समय बदला गया","kn":"ಮರುನಿಗದಿ","te":"రీషెడ్యూల్ చేయబడింది","ar":"أعيدت جدولته"}'::jsonb),
  ('other',              '{"en":"Other","hi":"अन्य","kn":"ಇತರೆ","te":"ఇతర","ar":"أخرى"}'::jsonb)
), pairs(kind, code) AS (VALUES
  ('cancel','schedule_clash'),('cancel','illness'),('cancel','cost'),('cancel','transport'),('cancel','went_elsewhere'),
  ('cancel','clinic_initiated'),('cancel','doctor_unavailable'),('cancel','rescheduled'),('cancel','other'),
  ('no_show','schedule_clash'),('no_show','illness'),('no_show','cost'),('no_show','transport'),('no_show','went_elsewhere'),('no_show','other'),
  ('reschedule','schedule_clash'),('reschedule','illness'),('reschedule','cost'),('reschedule','transport'),
  ('reschedule','clinic_initiated'),('reschedule','doctor_unavailable'),('reschedule','other'),
  ('override','clinic_initiated'),('override','doctor_unavailable'),('override','other'),
  ('refusal','schedule_clash'),('refusal','clinic_initiated'),('refusal','doctor_unavailable'),('refusal','other')
)
INSERT INTO reason_code_catalogue (kind, code, market, labels, requires_note, sort)
SELECT p.kind, p.code, m.market, l.l, p.code = 'other', row_number() OVER (PARTITION BY p.kind, m.market ORDER BY (p.code = 'other'), p.code)::int
FROM pairs p JOIN labels l USING (code) CROSS JOIN (VALUES ('IN'),('AE'),('SA'),('QA')) AS m(market);

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON reason_code_catalogue FROM nabd_app;
    GRANT SELECT ON reason_code_catalogue TO nabd_app;
  END IF;
END $$;

CREATE TABLE tenant_reason_codes (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id  uuid    NOT NULL REFERENCES tenants(id),
  kind       text    NOT NULL CHECK (kind IN ('cancel','no_show','reschedule','override','refusal')),
  code       text    NOT NULL CHECK (code ~ '^[a-z0-9_]{3,40}$'),
  labels     jsonb   NOT NULL CHECK (labels ? 'en'),
  hidden     boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, kind, code)
);
SELECT enforce_tenant_rls('tenant_reason_codes');

-- Appointments: cancel_reason stays as the free-text note; cancel_reason_code is the coded reason.
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS cancel_reason_code text;
UPDATE appointments SET cancel_reason_code = CASE WHEN cancel_reason = 'rescheduled' THEN 'rescheduled' ELSE 'other' END
  WHERE status = 'cancelled' AND cancel_reason_code IS NULL;

-- A direct write that cancels without a code (legacy SQL, scripts) still lands on 'other'; the API refuses it earlier (400).
CREATE FUNCTION appointments_default_cancel_code() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status = 'cancelled' AND NEW.cancel_reason_code IS NULL THEN
    NEW.cancel_reason_code := 'other';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_appointments_default_cancel_code BEFORE INSERT OR UPDATE ON appointments
  FOR EACH ROW EXECUTE FUNCTION appointments_default_cancel_code();

DO $$
DECLARE c text;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint WHERE conrelid = 'appointments'::regclass AND contype = 'c'
             AND pg_get_constraintdef(oid) LIKE '%cancel_reason IS NOT NULL%' LOOP
    EXECUTE format('ALTER TABLE appointments DROP CONSTRAINT %I', c);
  END LOOP;
END $$;
ALTER TABLE appointments ADD CONSTRAINT appointments_cancel_code_check
  CHECK ((status = 'cancelled') = (cancel_reason_code IS NOT NULL)) NOT VALID;
ALTER TABLE appointments VALIDATE CONSTRAINT appointments_cancel_code_check;
