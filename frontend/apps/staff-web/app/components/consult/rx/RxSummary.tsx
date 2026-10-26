"use client";

import StatusPill from "../../ui/StatusPill";
import { rxItemLine, type PrescriptionItem } from "../shared";
import p from "../consultParts.module.css";

// FEF-030: "Prescription" card of the side column.
export default function RxSummary({ items, status, onOpen }: { items: PrescriptionItem[]; status: "draft" | "signed"; onOpen: () => void }) {
  const drugs = items.filter((i) => i.drugName.trim());
  return (
    <section className={p.card} aria-label="Prescription summary">
      <h3 className={p.cardTitle}>
        Prescription
        {drugs.length === 0 ? <StatusPill tone="neutral" icon="list" label="None" /> : status === "signed" ? <StatusPill tone="ok" label="Signed" /> : <StatusPill tone="warn" label="Draft" />}
      </h3>
      {drugs.length > 0 ? <ul className={p.list}>{drugs.map((i, n) => <li key={n}>{rxItemLine(i)}</li>)}</ul> : <p className={p.muted}>No drugs added.</p>}
      <button type="button" className={`${p.linkBtn} ${p.linkBtnTouch}`} onClick={onOpen}>Open prescription</button>
    </section>
  );
}
