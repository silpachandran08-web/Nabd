SET lock_timeout = '5s';
-- CLN-042: doctor specialty profiles. specialty_profiles is Nabd-defined regional reference data (no tenant_id, no RLS,
-- SELECT-only for nabd_app): a new profile or config version ships as a migration (the versioned import), never from the
-- app. config.module is the plan entitlement code and the permission prefix (<module>:view|edit) of the profile; the
-- staff-web workspace renders config.widgets / quickActions from data, so a new code needs no shell change.
-- Names are the FRD profile names (prototype doctor.specialty); widget/action lists are a first cut that only point at
-- screens that exist today and need clinical review per profile (fieldSets and templatePack stay empty until then).
CREATE TABLE specialty_profiles (
  code           text PRIMARY KEY CHECK (code ~ '^[A-Z]{2,8}$'),
  name           jsonb NOT NULL CHECK (jsonb_typeof(name) = 'object' AND name ? 'en'),
  config         jsonb NOT NULL CHECK (
                   config->>'module' ~ '^specialty_[a-z0-9_]{1,30}$'
                   AND jsonb_typeof(config->'widgets') = 'array'
                   AND jsonb_typeof(config->'quickActions') = 'array'
                   AND jsonb_typeof(config->'fieldSets') = 'array'
                   AND config ? 'templatePack'),
  config_version int NOT NULL DEFAULT 1 CHECK (config_version > 0)
);
CREATE UNIQUE INDEX specialty_profiles_module ON specialty_profiles ((config->>'module'));

INSERT INTO specialty_profiles (code, name, config) VALUES
('GP',   '{"en":"General practice","hi":"सामान्य चिकित्सा","ar":"الطب العام"}',
         '{"module":"specialty_gp","widgets":[],"quickActions":[{"id":"consult","href":"/consult","label":{"en":"Quick consult","hi":"त्वरित परामर्श","ar":"استشارة سريعة"}},{"id":"rx","href":"/prescriptions","label":{"en":"e-Rx","hi":"e-Rx","ar":"وصفة إلكترونية"}},{"id":"followUp","href":"/recalls","label":{"en":"Follow-up order","hi":"फ़ॉलो-अप ऑर्डर","ar":"أمر متابعة"}}],"fieldSets":[],"templatePack":null}'),
('DERM', '{"en":"Dermatology & cosmetology","hi":"त्वचा और कॉस्मेटोलॉजी","ar":"الجلدية والتجميل"}',
         '{"module":"specialty_derm","widgets":[{"id":"photoTimeline","label":{"en":"Photo timeline"}}],"quickActions":[{"id":"consult","href":"/consult","label":{"en":"Start consult","hi":"परामर्श शुरू करें","ar":"ابدأ الاستشارة"}},{"id":"rx","href":"/prescriptions","label":{"en":"Write e-Rx","hi":"e-Rx लिखें","ar":"كتابة وصفة"}},{"id":"followUp","href":"/recalls","label":{"en":"Follow-up order","hi":"फ़ॉलो-अप ऑर्डर","ar":"أمر متابعة"}}],"fieldSets":[],"templatePack":null}'),
('DENT', '{"en":"Dentistry","hi":"दंत चिकित्सा","ar":"طب الأسنان"}',
         '{"module":"specialty_dental","widgets":[{"id":"toothChart","label":{"en":"Dental chart"}}],"quickActions":[{"id":"toothChart","href":"/patients","writes":true,"label":{"en":"Open dental chart","hi":"डेंटल चार्ट खोलें","ar":"فتح مخطط الأسنان"}},{"id":"consult","href":"/consult","label":{"en":"Start consult","hi":"परामर्श शुरू करें","ar":"ابدأ الاستشارة"}},{"id":"rx","href":"/prescriptions","label":{"en":"e-Rx","hi":"e-Rx","ar":"وصفة إلكترونية"}}],"fieldSets":[],"templatePack":null}'),
('PED',  '{"en":"Paediatrics","hi":"बाल रोग","ar":"طب الأطفال"}',
         '{"module":"specialty_ped","widgets":[{"id":"growthChart","label":{"en":"Growth chart"}}],"quickActions":[{"id":"consult","href":"/consult","label":{"en":"Quick consult","hi":"त्वरित परामर्श","ar":"استشارة سريعة"}},{"id":"followUp","href":"/recalls","label":{"en":"Follow-up order","hi":"फ़ॉलो-अप ऑर्डर","ar":"أمر متابعة"}}],"fieldSets":[],"templatePack":null}'),
('ENT',  '{"en":"ENT","hi":"नाक, कान, गला","ar":"الأنف والأذن والحنجرة"}',
         '{"module":"specialty_ent","widgets":[],"quickActions":[{"id":"consult","href":"/consult","label":{"en":"Quick consult","hi":"त्वरित परामर्श","ar":"استشارة سريعة"}},{"id":"followUp","href":"/recalls","label":{"en":"Follow-up order","hi":"फ़ॉलो-अप ऑर्डर","ar":"أمر متابعة"}}],"fieldSets":[],"templatePack":null}'),
('ORTH', '{"en":"Orthopaedics & physio","hi":"हड्डी रोग और फ़िज़ियो","ar":"العظام والعلاج الطبيعي"}',
         '{"module":"specialty_orth","widgets":[],"quickActions":[{"id":"consult","href":"/consult","label":{"en":"Quick consult","hi":"त्वरित परामर्श","ar":"استشارة سريعة"}},{"id":"followUp","href":"/recalls","label":{"en":"Follow-up order","hi":"फ़ॉलो-अप ऑर्डर","ar":"أمر متابعة"}}],"fieldSets":[],"templatePack":null}'),
('GYN',  '{"en":"Obstetrics & gynaecology","hi":"स्त्री एवं प्रसूति रोग","ar":"النساء والولادة"}',
         '{"module":"specialty_gyn","widgets":[],"quickActions":[{"id":"consult","href":"/consult","label":{"en":"Quick consult","hi":"त्वरित परामर्श","ar":"استشارة سريعة"}},{"id":"followUp","href":"/recalls","label":{"en":"Follow-up order","hi":"फ़ॉलो-अप ऑर्डर","ar":"أمر متابعة"}}],"fieldSets":[],"templatePack":null}'),
('HOM',  '{"en":"Homeopathy","hi":"होम्योपैथी","ar":"المعالجة المثلية"}',
         '{"module":"specialty_hom","widgets":[],"quickActions":[{"id":"consult","href":"/consult","label":{"en":"Quick consult","hi":"त्वरित परामर्श","ar":"استشارة سريعة"}},{"id":"followUp","href":"/recalls","label":{"en":"Follow-up order","hi":"फ़ॉलो-अप ऑर्डर","ar":"أمر متابعة"}}],"fieldSets":[],"templatePack":null}'),
('AYU',  '{"en":"Ayurveda (AYUSH)","hi":"आयुर्वेद (आयुष)","ar":"الأيورفيدا"}',
         '{"module":"specialty_ayu","widgets":[],"quickActions":[{"id":"consult","href":"/consult","label":{"en":"Quick consult","hi":"त्वरित परामर्श","ar":"استشارة سريعة"}},{"id":"followUp","href":"/recalls","label":{"en":"Follow-up order","hi":"फ़ॉलो-अप ऑर्डर","ar":"أمر متابعة"}}],"fieldSets":[],"templatePack":null}');

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON specialty_profiles FROM nabd_app;
    GRANT SELECT ON specialty_profiles TO nabd_app;
  END IF;
END $$;

-- A staff member holds at most one primary and one secondary profile. Non-PHI.
CREATE TABLE staff_specialty_profiles (
  tenant_id   uuid NOT NULL REFERENCES tenants (id),
  staff_id    uuid NOT NULL REFERENCES staff (id),
  code        text NOT NULL REFERENCES specialty_profiles (code),
  is_primary  boolean NOT NULL,
  assigned_by uuid NOT NULL,
  assigned_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, staff_id, code)
);
CREATE UNIQUE INDEX staff_specialty_profiles_primary ON staff_specialty_profiles (tenant_id, staff_id) WHERE is_primary;
CREATE UNIQUE INDEX staff_specialty_profiles_secondary ON staff_specialty_profiles (tenant_id, staff_id) WHERE NOT is_primary;
SELECT enforce_tenant_rls('staff_specialty_profiles');
