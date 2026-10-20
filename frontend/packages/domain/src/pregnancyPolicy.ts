// DOC-021: the ONE place that decides for whom the pregnancy/lactation status is prompted for.
// NEEDS CLINICAL SIGN-OFF: the sex and age band below are a configurable default, not an approved clinical
// rule (the backend RxSafetyChecks uses its own 12-55 band for drug warnings; align the two after sign-off).
// This only controls whether the strip prompts ("not documented, ask"); a status that is recorded is always
// shown, and the status can be recorded for any patient.
export const PREGNANCY_PROMPT_SEX = "female";
export const PREGNANCY_PROMPT_MIN_AGE = 12;
export const PREGNANCY_PROMPT_MAX_AGE = 55;

export function pregnancyPromptRelevant(gender: string | undefined, ageYears: number): boolean {
  return (gender ?? "").toLowerCase() === PREGNANCY_PROMPT_SEX && ageYears >= PREGNANCY_PROMPT_MIN_AGE && ageYears <= PREGNANCY_PROMPT_MAX_AGE;
}
