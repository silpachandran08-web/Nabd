"use client";
// The note field headings (shared domain NOTE_FIELD_LABEL ids) as glossary terms, so SOAP wording is clinician-reviewed in every language.
import { useTranslations } from "next-intl";
import type { NoteFieldId } from "@nabd/domain";

export function useNoteFieldLabels(): Record<NoteFieldId, string> {
  const g = useTranslations("glossary");
  return { subjective: g("soap.subjective"), objective: g("soap.objective"), assessment: g("soap.assessment"), plan: g("soap.plan"), diagnosis: g("diagnosis.label") };
}
