"use client";

// IDC-003: when a patient's latest consent names an older privacy notice than the clinic's current one, the desk sees one
// prompt and one tap records the acknowledgement. Care is never blocked. Absent without patients:view; the button
// is absent without patients:edit.
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import styles from "../../(clinic)/setup/setup.module.css";
import { can } from "../../lib/permissions";
import { api, errorText } from "../../lib/api";
import { getter, key } from "../../lib/query";
import { reackText, type NoticeStatus } from "../../lib/privacyNotice";

export default function ReackPrompt({ patientId }: { patientId: string }) {
  const qc = useQueryClient();
  const k = key("privacy-notice", { id: patientId });
  const q = useQuery({ queryKey: k, queryFn: getter<NoticeStatus>(`/patients/${patientId}/privacy-notice`), enabled: can("patients:view"), staleTime: 30_000 });
  const ack = useMutation({
    mutationFn: () => api.post<NoticeStatus>(`/patients/${patientId}/privacy-notice/acknowledge`),
    onSuccess: (s) => qc.setQueryData(k, s),
  });
  const text = reackText(q.data);
  if (!text) return null;
  return (
    <div className={styles.notice} role="status">
      <div>{text}</div>
      {can("patients:edit") && (
        <button type="button" className={styles.btnPrimary} style={{ minBlockSize: 44 }} onClick={() => ack.mutate()} disabled={ack.isPending}>
          Patient re-acknowledged
        </button>
      )}
      {ack.isError && <div role="alert">{errorText(ack.error, "Could not record the acknowledgement.")}</div>}
    </div>
  );
}
