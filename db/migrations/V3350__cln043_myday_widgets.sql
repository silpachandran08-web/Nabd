SET lock_timeout = '5s';
-- CLN-043: my-day widgets and the consult quick-action bar follow the doctor's specialty profile (CLN-042 registry).
-- config.myDay = { widgets: [widget id], visitTypeChips?: true, ageInMonthsUnder2?: true }. The widget ids are the FRD
-- "Doctor Specialization Profiles" home widgets; staff-web renders an id only when its widget (component + data source +
-- permission) is built, so the ids of features that do not exist yet stay hidden until their story lands. No clinical
-- content here (labels and data come from the owning features). The quick-action bar holds at most 6 actions: a config
-- with a 7th is refused by this schema check, so it can never ship.
ALTER TABLE specialty_profiles ADD CONSTRAINT specialty_profiles_config_cln043 CHECK (
  jsonb_array_length(config->'quickActions') <= 6
  AND (NOT config ? 'myDay' OR (jsonb_typeof(config->'myDay') = 'object'
                                 AND jsonb_typeof(config->'myDay'->'widgets') = 'array'
                                 AND jsonb_array_length(config->'myDay'->'widgets') <= 12))
) NOT VALID;
ALTER TABLE specialty_profiles VALIDATE CONSTRAINT specialty_profiles_config_cln043;

UPDATE specialty_profiles SET config_version = config_version + 1, config = config || jsonb_build_object('myDay', CASE code
  WHEN 'GP'   THEN '{"widgets":["waitingCount","registersDue"]}'::jsonb
  WHEN 'DERM' THEN '{"widgets":["photosDue"],"visitTypeChips":true}'::jsonb
  WHEN 'DENT' THEN '{"widgets":["inChairNow","pendingPlans","labDueBack"]}'::jsonb
  WHEN 'PED'  THEN '{"widgets":["vaccinesDueToday"],"ageInMonthsUnder2":true}'::jsonb
  WHEN 'ENT'  THEN '{"widgets":["procedurePatients","pendingConsents"]}'::jsonb
  WHEN 'ORTH' THEN '{"widgets":["xraysPendingReview","castRemovalDue"]}'::jsonb
  WHEN 'GYN'  THEN '{"widgets":["pregnantByWeek","ancOverdue","ultrasoundsPending"]}'::jsonb
  WHEN 'HOM'  THEN '{"widgets":["followUpsDue","longCasesPending"]}'::jsonb
  WHEN 'AYU'  THEN '{"widgets":["therapyToday","packageSessionsRemaining"]}'::jsonb
  END)
WHERE code IN ('GP', 'DERM', 'DENT', 'PED', 'ENT', 'ORTH', 'GYN', 'HOM', 'AYU');
