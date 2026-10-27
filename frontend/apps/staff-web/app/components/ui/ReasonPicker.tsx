"use client";

import { useQuery } from "@tanstack/react-query";
import { useLocale } from "next-intl";
import { getter, key } from "../../lib/query";
import styles from "./reasonPicker.module.css";
import type { ReasonItem } from "../../lib/reasonPicker";

export type { ReasonItem };

/**
 * ACC-007: the one coded reason list (GET /v1/reason-codes?kind=…&locale=…) as radios, in the viewer's language.
 * A reason that requires a note (`other`) shows a note field (max 200); askNote={false} hides it for kinds whose API takes no note.
 * The parent keeps the chosen item and note, and decides when the form may be submitted.
 */
export default function ReasonPicker({ kind, value, onChange, note, onNote, askNote = true, legend, name }: {
  kind: "cancel" | "no_show" | "reschedule" | "override" | "refusal";
  value: ReasonItem | null; onChange: (item: ReasonItem) => void;
  note?: string; onNote?: (note: string) => void; askNote?: boolean; legend: string; name: string;
}) {
  const locale = useLocale();
  const q = useQuery({
    queryKey: key("reason-codes", { kind, locale }),
    queryFn: getter<{ items: ReasonItem[] }>(`/reason-codes?kind=${kind}&locale=${encodeURIComponent(locale)}`),
    staleTime: 5 * 60_000,
  });
  if (q.isPending) return <p className={styles.state} role="status">Loading reasons…</p>;
  if (q.isError) return (
    <div className={styles.state} role="alert">
      Couldn&apos;t load the reasons. <button type="button" className={styles.retry} onClick={() => void q.refetch()}>Retry</button>
    </div>
  );
  const items = q.data?.items ?? [];
  if (items.length === 0) return <p className={styles.state}>No reasons are set up for this action yet.</p>;
  return (
    <fieldset className={styles.set}>
      <legend className={styles.legend}>{legend}</legend>
      {items.map((r) => (
        <label key={r.code} className={value?.code === r.code ? styles.on : styles.reason}>
          <input type="radio" name={name} value={r.code} checked={value?.code === r.code} onChange={() => onChange(r)} /> {r.label}
        </label>
      ))}
      {askNote && value?.requiresNote && (
        <label className={styles.noteLabel}>
          Note (required, max 200 characters)
          <textarea className={styles.note} maxLength={200} rows={2} value={note ?? ""} onChange={(e) => onNote?.(e.target.value)} />
        </label>
      )}
    </fieldset>
  );
}
