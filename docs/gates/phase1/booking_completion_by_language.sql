-- FEF-041: booking completion per language (target: each of hi, kn, te >= 90% of the English rate over 4 weeks of the pilot).
-- Run against the EDGE database (view funnel_completion_by_lang, Edge V23; counts and a language code only, no PHI). A clinic's
-- rows need its tenant bound (app.tenant_id); the pilot report runs it once per clinic. lang NULL = left before choosing a language.
-- The 4-week measurement itself needs live pilot traffic and is external (owner: Patient Access lead).
SELECT lang, started, completed, round(100.0 * completed / nullif(started, 0), 1) AS completion_pct,
       round(100.0 * (completed::numeric / nullif(started, 0)) / nullif(max(completed::numeric / nullif(started, 0)) FILTER (WHERE lang = 'en') OVER (), 0), 1) AS pct_of_english
FROM funnel_completion_by_lang
ORDER BY lang;
