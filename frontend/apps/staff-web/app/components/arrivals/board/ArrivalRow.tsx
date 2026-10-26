"use client";

import { memo } from "react";
import { useTranslations } from "next-intl";
import styles from "../arrivals.module.css";
import StatusPill from "../../ui/StatusPill";
import { useRenderProbe } from "../renderProbe";
import type { Grants, Row } from "../types";
import { bucketOf } from "./boardLogic";
import { visitLabel } from "./visitLabel";
import RowActions, { type RowHandlers } from "./RowActions";
import StatusBadge from "./StatusBadge";
import WaitCell from "./WaitCell";

type Props = { row: Row; doctorName: string; grants: Grants; sendingId?: string; h: RowHandlers; onOpen: (id: string) => void };

const shallow = (a: object, b: object) => {
  const ka = Object.keys(a) as (keyof typeof a)[];
  return ka.length === Object.keys(b).length && ka.every((k) => Object.is(a[k], b[k as keyof typeof b]));
};
// Rows are rebuilt from the query data on every page render, so compare by value: a poll that changed nothing
// (or one other row) leaves this row alone.
const same = (p: Props, n: Props) =>
  shallow(p.row, n.row) && shallow(p.grants, n.grants) && p.doctorName === n.doctorName && p.sendingId === n.sendingId && p.h === n.h && p.onOpen === n.onOpen;

function ArrivalRowImpl({ row: r, doctorName, grants, sendingId, h, onOpen }: Props) {
  useRenderProbe("arrivals-row");
  const t = useTranslations("arrivals");
  const b = bucketOf(r.status);
  return (
    <tr className={styles.row} tabIndex={0} aria-label={t("board.rowLabel", { name: r.patientName, token: r.tokenNumber })}
      onClick={() => onOpen(r.id)}
      onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); onOpen(r.id); } }}>
      <td className={styles.token} data-label={t("board.token")}>
        #{r.tokenNumber}
        {r.priority && <span className={styles.priorityTag}><StatusPill tone="urgent" label={t("board.priority")} /></span>}
      </td>
      <td data-label={t("board.patient")}><span className={styles.patientName}>{r.patientName}</span>
        <span className={styles.mrn}>{[r.patientMrn, [r.ageYears, (r.gender ?? "").trim().charAt(0).toUpperCase()].filter((x) => x != null && x !== "").join(" ")].filter(Boolean).join(" · ")}</span>
        {r.presentingComplaint && <span className={styles.complaint} title={r.presentingComplaint}>{r.presentingComplaint}</span>}</td>
      <td className={`${styles.muted} ${styles.colVisit}`} data-label={t("board.visit")}>{visitLabel(t, r)}</td>
      <td className={styles.colDoctor} data-label={t("board.doctor")}>{doctorName}</td>
      <td className={styles.muted} data-label={t("board.wait")}>{b === "waiting" || b === "in_consult" ? <WaitCell createdAt={r.createdAt} /> : "—"}</td>
      <td data-label={t("board.status")}><StatusBadge status={r.status} /></td>
      <td onClick={(e) => e.stopPropagation()}>
        <div className={styles.rowActions}><RowActions row={r} grants={grants} sendingId={sendingId} h={h} /></div>
      </td>
    </tr>
  );
}
export default memo(ArrivalRowImpl, same);
