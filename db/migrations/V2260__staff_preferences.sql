-- FEF-036: per-staff UI preferences (locale, theme, digit style). Non-PHI. FEF-025 reads ui_theme.
SET lock_timeout = '5s';

CREATE TABLE staff_preferences (
  staff_id    uuid PRIMARY KEY REFERENCES staff(id),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  ui_locale   text NULL CHECK (ui_locale IN ('en','hi','kn','te','ta','ml','bn','ar')),
  ui_theme    text NOT NULL DEFAULT 'system' CHECK (ui_theme IN ('system','dark','light')),
  digit_style text NULL CHECK (digit_style IN ('latn','arab')),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_staff_preferences_updated_at BEFORE UPDATE ON staff_preferences
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

SELECT enforce_tenant_rls('staff_preferences');
