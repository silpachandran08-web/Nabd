"use client";

// FEF-035: the prescription changed on the server while this device held unsaved lines (another device, or a draft
// restored after a crash). Both versions are shown; nothing is overwritten until the doctor picks one.
import { useTranslations } from "next-intl";
import Dialog from "../../ui/Dialog";
import { rxItemLine, type PrescriptionItem } from "../shared";
import s from "../consultParts.module.css";

function Lines({ items, empty }: { items: PrescriptionItem[]; empty: string }) {
  const named = items.filter((i) => i.drugName.trim() !== "");
  return named.length === 0 ? <p className={s.muted}>{empty}</p> : <ul className={s.list}>{named.map((i, k) => <li key={k}>{rxItemLine(i)}</li>)}</ul>;
}

export default function RxConflictDialog({ mine, server, onResolve, onClose }: {
  mine: PrescriptionItem[]; server: PrescriptionItem[]; onResolve: (keep: "mine" | "server") => void; onClose: () => void;
}) {
  const tx = useTranslations("consult.rxConflictDialog");
  return (
    <Dialog title={tx("title")} onClose={onClose} size="lg">
      <p className={s.muted}>{tx("intro")}</p>
      <div className={`${s.stack} ${s.mtM}`}>
        <section className={`${s.card} ${s.m0}`} aria-label={tx("mine")}>
          <h4 className={s.cardTitle}>{tx("mine")}</h4>
          <Lines items={mine} empty={tx("empty")} />
        </section>
        <section className={`${s.card} ${s.m0}`} aria-label={tx("server")}>
          <h4 className={s.cardTitle}>{tx("server")}</h4>
          <Lines items={server} empty={tx("empty")} />
        </section>
      </div>
      <div className={s.dialogActions}>
        <button type="button" className={s.btn} onClick={onClose}>{tx("decideLater")}</button>
        <button type="button" className={s.btn} onClick={() => onResolve("server")}>{tx("keepServer")}</button>
        <button type="button" className={s.btnPrimary} onClick={() => onResolve("mine")}>{tx("keepMine")}</button>
      </div>
    </Dialog>
  );
}
