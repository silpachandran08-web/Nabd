-- FEF-036: clinic default UI language, the second step of locale resolution (user -> clinic -> browser). Nullable = no default.
SET lock_timeout = '5s';

ALTER TABLE tenants ADD COLUMN default_locale text;
ALTER TABLE tenants ADD CONSTRAINT tenants_default_locale_chk
  CHECK (default_locale IS NULL OR default_locale IN ('en','hi','kn','te','ta','ml','bn','ar')) NOT VALID;
ALTER TABLE tenants VALIDATE CONSTRAINT tenants_default_locale_chk;
