"use client";
// DOC_LABEL / DOC_FIELDS labels (documents.ts) through the catalogue; the clinical headings are glossary terms.
import { useTranslations } from "next-intl";
import type { DocType } from "./documents";

export function useDocText() {
  const c = useTranslations("consult.documents");
  const g = useTranslations("glossary");
  const clinical: Record<string, string> = { diagnosis: g("diagnosis.label"), assessment: g("soap.assessment"), plan: g("soap.plan"), medicines: g("rx.medicines") };
  return { label: (type: DocType) => c(`label.${type}`), field: (key: string) => clinical[key] ?? c(`field.${key}`) };
}
