"use client";

// PHL-043: a clinic without the lab module: the doctor types the tests and prints an order sheet for a lab outside Nabd.
import { useState } from "react";
import { useTranslations } from "next-intl";
import { api } from "../../lib/api";
import { emptySheet, sheetBody, sheetError, type SheetForm } from "../../lib/labOrders";
import Icon from "../ui/Icon";
import { Fasting, PriorityToggle, openPdf } from "./LabTestsParts";
import type { Row } from "./shared";
import s from "./consultParts.module.css";
import c from "./labTests.module.css";

export default function ExternalSheet({ entry }: { entry: Row }) {
  const t = useTranslations("consult.labTests");
  const [form, setForm] = useState<SheetForm>(emptySheet);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  async function print() {
    const err = sheetError(form);
    if (busy) return;
    if (err) { setProblem(t(`sheetErrors.${err}`)); return; }
    setBusy(true); setProblem(null); setDone(false);
    const ok = await openPdf(() => api.post<Blob>(`/clinical/visits/${entry.id}/lab-order-sheet`, sheetBody(form), { as: "blob" }));
    setDone(ok);
    if (!ok) setProblem(t("sheetFailed"));
    setBusy(false);
  }

  return (
    <div onKeyDown={(e) => { if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); void print(); } }}>
      <p className={s.muted}>{t("externalOnly")}</p>
      <div className={c.field}>
        <label htmlFor="lab-sheet-tests">{t("sheetTests")}</label>
        <textarea id="lab-sheet-tests" className={c.textarea} value={form.tests} onChange={(e) => setForm((f) => ({ ...f, tests: e.target.value }))} />
        <span className={s.muted}>{t("sheetTestsHint")}</span>
      </div>
      <div className={c.field}>
        <label htmlFor="lab-sheet-lab">{t("sheetLab")}</label>
        <input id="lab-sheet-lab" className={`${s.input} ${c.input} ${c.target}`} maxLength={120} value={form.labName} onChange={(e) => setForm((f) => ({ ...f, labName: e.target.value }))} />
      </div>
      <div className={c.field}><PriorityToggle value={form.priority} onChange={(priority) => setForm((f) => ({ ...f, priority }))} /></div>
      <div className={c.field}><Fasting on={form.fasting} hours={form.fastingHours} onChange={(fasting, fastingHours) => setForm((f) => ({ ...f, fasting, fastingHours }))} /></div>
      <div className={c.field}>
        <label htmlFor="lab-sheet-note">{t("note")}</label>
        <textarea id="lab-sheet-note" className={c.textarea} maxLength={500} value={form.note} onChange={(e) => setForm((f) => ({ ...f, note: e.target.value }))} />
      </div>
      <button type="button" className={`${s.btnPrimary} ${c.target}`} disabled={busy} onClick={() => void print()}>{busy ? t("printing") : t("printSheet")}</button>
      {problem && <div className={`${s.notice} ${s.noticeDanger} ${s.mtS}`} role="alert"><Icon name="alert" size={16} /><span>{problem}</span></div>}
      {done && <div className={`${s.notice} ${s.noticeOk} ${s.mtS}`} role="status"><Icon name="check" size={16} /><span>{t("sheetOpened")}</span></div>}
    </div>
  );
}
