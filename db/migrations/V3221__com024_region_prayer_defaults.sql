SET lock_timeout = '5s';
-- COM-024 (OPS-061): the prayer-window default and calculation method are region reference data, not code.
-- The method each GCC authority publishes; elsewhere Muslim World League. NEEDS LOCAL CHECK (DEC-27 can flip the defaults).
ALTER TABLE regions
  ADD COLUMN IF NOT EXISTS prayer_method text NOT NULL DEFAULT 'mwl' CHECK (prayer_method IN ('umm_al_qura', 'qatar', 'dubai', 'mwl')),
  ADD COLUMN IF NOT EXISTS prayer_windows_default boolean NOT NULL DEFAULT false;
UPDATE regions SET prayer_method = 'umm_al_qura', prayer_windows_default = true WHERE code = 'SA';
UPDATE regions SET prayer_method = 'qatar' WHERE code = 'QA';
UPDATE regions SET prayer_method = 'dubai' WHERE code = 'AE';
