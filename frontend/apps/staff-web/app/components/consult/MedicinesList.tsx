"use client";

import StatusPill from "../ui/StatusPill";
import { Skeleton } from "../ui/StateViews";
import type { Part } from "./clinical";
import { rxItemLine, type Prescription } from "./shared";
import s from "./consultParts.module.css";

/** Previous/current medicines with dose, sig and duration (DOC-037). Never hidden when empty. */
export default function MedicinesList({ prescriptions, onRetry }: { prescriptions: Part<Prescription[]>; onRetry: () => void }) {
  if (prescriptions.status === "loading") return <Skeleton rows={2} label="Loading medicines" />;
  if (prescriptions.status === "error") {
    return <p className={s.muted}>Couldn&apos;t load previous medicines. <button type="button" className={s.linkBtn} onClick={onRetry}>Retry</button></p>;
  }
  const list = prescriptions.data ?? [];
  if (list.length === 0) return <p className={s.muted}>No previous medicines recorded.</p>;
  return (
    <ul className={s.list}>
      {list.map((rx) => (
        <li key={rx.id}>
          <div className={s.listRow}>
            {rx.signedAt ? <span className={s.muted}>{new Date(rx.signedAt).toLocaleDateString()}</span> : <span className={s.muted}>Unsigned</span>}
            <StatusPill tone={rx.status === "signed" ? "ok" : "warn"} label={rx.status === "signed" ? "Signed" : "Draft"} />
          </div>
          {rx.items.length === 0 ? <span className={s.muted}>No items</span> : rx.items.map((i, n) => <div key={i.id ?? n}>{rxItemLine(i)}</div>)}
        </li>
      ))}
    </ul>
  );
}
