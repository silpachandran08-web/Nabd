"use client";

import { useTranslations } from "next-intl";
import StatusPill from "../ui/StatusPill";
import { Skeleton } from "../ui/StateViews";
import type { Part } from "./clinical";
import { rxItemLine, type Prescription } from "./shared";
import s from "./consultParts.module.css";

/** Previous/current medicines with dose, sig and duration (DOC-037). Never hidden when empty. */
export default function MedicinesList({ prescriptions, onRetry }: { prescriptions: Part<Prescription[]>; onRetry: () => void }) {
  const tx = useTranslations("consult.medicinesList");
  if (prescriptions.status === "loading") return <Skeleton rows={2} label={tx("loadingMedicines")} />;
  if (prescriptions.status === "error") {
    return <p className={s.muted}>{tx("couldnTLoadPreviousMedicines")}{" "}<button type="button" className={s.linkBtn} onClick={onRetry}>{tx("retry")}</button></p>;
  }
  const list = prescriptions.data ?? [];
  if (list.length === 0) return <p className={s.muted}>{tx("noPreviousMedicinesRecorded")}</p>;
  return (
    <ul className={s.list}>
      {list.map((rx) => (
        <li key={rx.id}>
          <div className={s.listRow}>
            {rx.signedAt ? <span className={s.muted}>{new Date(rx.signedAt).toLocaleDateString()}</span> : <span className={s.muted}>{tx("unsigned")}</span>}
            <StatusPill tone={rx.status === "signed" ? "ok" : "warn"} label={rx.status === "signed" ? tx("signed") : tx("draft")} />
          </div>
          {rx.items.length === 0 ? <span className={s.muted}>{tx("noItems")}</span> : rx.items.map((i, n) => <div key={i.id ?? n}>{rxItemLine(i)}</div>)}
        </li>
      ))}
    </ul>
  );
}
