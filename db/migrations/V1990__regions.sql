-- OPS-061 (MF-21 / QA-21): region reference data. One row per supported region; every regional rule (currency, tax regime,
-- regulator, time zone, phone country, cell) is read from here, not switched on a code in Java.
-- Platform reference table: no tenant_id, read-only to the API role (changes ship as migrations).
-- Structural data only. standard_tax_rate is the headline rate the story names (AE 5, SA 15, QA no VAT = 0); India GST has
-- several slabs per item, so it is NULL. phone_nsn_length is advisory: validation itself is libphonenumber (PhoneNumbers).
SET lock_timeout = '5s';

CREATE TABLE regions (
  code               char(2) PRIMARY KEY CHECK (code IN ('IN', 'AE', 'SA', 'QA')),
  name               text NOT NULL,
  currency           char(3) NOT NULL,
  minor_units        smallint NOT NULL,
  tax_regime         text NOT NULL,
  standard_tax_rate  numeric(5,2),
  regulator          text NOT NULL,
  default_timezone   text NOT NULL,
  phone_country_code text NOT NULL,
  phone_nsn_length   int[] NOT NULL,
  cell               text NOT NULL,
  active             boolean NOT NULL DEFAULT true
);

INSERT INTO regions (code, name, currency, minor_units, tax_regime, standard_tax_rate, regulator, default_timezone, phone_country_code, phone_nsn_length, cell) VALUES
  ('IN', 'India',                'INR', 2, 'GST', NULL,  'NMC/CDSCO',     'Asia/Kolkata', '+91',  '{10}',  'in'),
  ('AE', 'United Arab Emirates', 'AED', 2, 'VAT', 5.00,  'MOHAP/DHA/DoH', 'Asia/Dubai',   '+971', '{8,9}', 'ae'),
  ('SA', 'Saudi Arabia',         'SAR', 2, 'VAT', 15.00, 'MOH/SFDA',      'Asia/Riyadh',  '+966', '{9}',   'sa'),
  ('QA', 'Qatar',                'QAR', 2, 'NONE', 0.00, 'MOPH',          'Asia/Qatar',   '+974', '{8}',   'qa');

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE ALL ON regions FROM nabd_app;
    GRANT SELECT ON regions TO nabd_app;
  END IF;
END $$;
