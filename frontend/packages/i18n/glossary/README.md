# Clinical glossary (FEF-039, ADR-11)

Clinical terms (SOAP step names, allergy and pregnancy wording, warning titles, sig and Rx field names, vital signs) are defined once here and shown from one place.

- `terms.json`: one entry per term id, with a `definition` (what the term means on screen) and `approved`, the languages whose text a clinician has reviewed.
- `../messages/<lang>/glossary.json`: the text of each term in that language. English is the source text.
- Code reads a term with `useTranslations("glossary")` and the term id (`g("allergy.warning.title")`). Catalogue entries must not repeat a glossary text (checked by `tests/unit/s16-fef039-consult-i18n.test.mjs`).
- A translation file may hold only terms that list that language in `approved`. Never machine-translate a term: a language is added to `approved` by the clinician who reviewed it.
- Both files are CODEOWNERS-protected (`@nabd/clinical-owners`, see `.github/CODEOWNERS`); with `require_code_owner_reviews` a change cannot merge without their approval. The GitHub team itself is Needs external action.
