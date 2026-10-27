"use client";

// IDC-018: a patient registered by ABDM Scan & Share has no data-processing consent yet. While the consent state says one is
// needed, the desk sees one prompt that points to the patient record where consent is captured. Care is never blocked.
// Absent without patients:view.
import Link from "next/link";
import { useTranslations } from "next-intl";
import { useQuery } from "@tanstack/react-query";
import type { PatientConsentState } from "@nabd/api-client/generated";
import styles from "../../(clinic)/setup/setup.module.css";
import { can } from "../../lib/permissions";
import { getter, key } from "../../lib/query";

export default function ScanShareConsentPrompt({ patientId, mrn }: { patientId: string; mrn?: string | null }) {
  const t = useTranslations("arrivals.rail");
  const q = useQuery({ queryKey: key("consent-state", { id: patientId }), queryFn: getter<PatientConsentState>(`/patients/${patientId}/consents`), enabled: can("patients:view"), staleTime: 30_000 });
  if (!q.data?.needsConsent) return null;
  return (
    <div className={styles.notice} role="status" data-testid="scan-share-consent">
      <strong>{t("scanShareConsent")}</strong>
      <div>{t("scanShareConsentHint")}</div>
      {mrn && <Link href={`/patients?q=${encodeURIComponent(mrn)}`} style={{ minBlockSize: 44, display: "inline-flex", alignItems: "center" }}>{t("openRecord")}</Link>}
    </div>
  );
}
