"use client";

import { useEffect, useId, useState } from "react";
import { useTranslations } from "next-intl";
import styles from "../../(clinic)/checkout/checkout.module.css";
import { api, ApiError, errorText } from "../../lib/api";
import { clinicToday } from "../../lib/clinicTime";
import { paperRxErrorKey, paperRxProblem, photoProblem, uploadPaperRxPhoto } from "./paperRx";

export type PaperRxLink = { paperRxId: string; paperRxLineId: string; prescriberName: string; qty: number };
type Prescriber = { name: string; regNo: string };

// PHL-020: records an outside paper / WhatsApp-photo prescription for one prescription-only cart line and hands back the
// paperRxLineId the bill line carries. Every call here needs pharmacy:dispense, so the caller renders it only with that grant.
// The photo goes through the scanned upload pipeline first; a blocked photo never becomes a link.
export default function PaperRxDialog({ item, quantity, onLinked, onCancel }: {
  item: { id: string; name: string };
  quantity: number;
  onLinked: (link: PaperRxLink) => void;
  onCancel: () => void;
}) {
  const t = useTranslations("checkout.paperRx");
  const listId = useId();
  const [prescribers, setPrescribers] = useState<Prescriber[]>([]);
  const [name, setName] = useState("");
  const [regNo, setRegNo] = useState("");
  const [rxDate, setRxDate] = useState(() => clinicToday());
  const [qty, setQty] = useState(String(quantity));
  const [file, setFile] = useState<File | null>(null);
  const [copy, setCopy] = useState(false);
  const [needsCopy, setNeedsCopy] = useState(false); // set once the server says this item's class keeps a duplicate copy
  const [phase, setPhase] = useState<"uploading" | "saving" | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // autocomplete only: without it the four fields are still typed by hand
    api.get<Prescriber[]>("/pharmacy/paper-prescriptions/prescribers").then(setPrescribers, () => {});
  }, []);

  function pickName(value: string) {
    setName(value);
    const known = prescribers.find((p) => p.name === value);
    if (known) setRegNo(known.regNo);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    const bad = paperRxProblem({ prescriberName: name, prescriberRegNo: regNo, rxDate, qty: Number(qty), hasPhoto: file != null, duplicateCopyRetained: copy },
      clinicToday(), quantity, needsCopy) ?? (file ? photoProblem(file) : null);
    if (bad) {
      setError(t(bad));
      return;
    }
    try {
      let documentId: string | undefined;
      if (file) {
        setPhase("uploading");
        documentId = await uploadPaperRxPhoto(api, file).catch((err: Error) => { throw new PhotoError(err.message); });
      }
      setPhase("saving");
      const res = await api.post<{ paperRxId: string; lines: { paperRxLineId: string }[] }>("/pharmacy/paper-prescriptions", {
        prescriberName: name.trim(), prescriberRegNo: regNo.trim(), rxDate, lines: [{ itemId: item.id, qty: Number(qty) }],
        ...(documentId ? { documentId } : {}), duplicateCopyRetained: copy,
      });
      onLinked({ paperRxId: res.paperRxId, paperRxLineId: res.lines[0].paperRxLineId, prescriberName: name.trim(), qty: Number(qty) });
    } catch (err) {
      if (err instanceof PhotoError) {
        setFile(null); // a blocked or failed photo is dropped: a new one has to be taken
        setError(t(err.message));
      } else {
        const key = err instanceof ApiError ? paperRxErrorKey(err.problem) : null;
        if (key === "copyNeeded") setNeedsCopy(true);
        if (key && key !== "copyNeeded" && key !== "expired") setFile(null);
        setError(key ? t(key) : errorText(err, t("saveFailed")));
      }
    } finally {
      setPhase(null);
    }
  }

  return (
    <form className={styles.rxPicker} role="dialog" aria-label={t("dialog", { item: item.name })} onSubmit={submit}>
      <div className={styles.cardTitle}>{t("title", { item: item.name })}</div>
      <div className={styles.rxPickerRow}>
        <input aria-label={t("doctorName")} placeholder={t("doctorName")} className={styles.rxPickerInput} list={listId} maxLength={120}
          value={name} onChange={(e) => pickName(e.target.value)} />
        <datalist id={listId}>{prescribers.map((p) => <option key={`${p.name}|${p.regNo}`} value={p.name}>{p.regNo}</option>)}</datalist>
        <input aria-label={t("regNo")} placeholder={t("regNo")} className={styles.rxPickerInput} maxLength={60}
          value={regNo} onChange={(e) => setRegNo(e.target.value)} />
      </div>
      <div className={styles.rxPickerRow}>
        <label className={styles.muted}>{t("rxDate")}
          <input type="date" className={styles.rxPickerInput} max={clinicToday()} value={rxDate} onChange={(e) => setRxDate(e.target.value)} />
        </label>
        <label className={styles.muted}>{t("qty")}
          <input type="number" inputMode="numeric" min={1} step={1} className={styles.rxPickerInput} value={qty} onChange={(e) => setQty(e.target.value)} />
        </label>
      </div>
      <div className={styles.rxPickerRow}>
        <label className={styles.muted}>{needsCopy ? t("photoRequired") : t("photo")}
          {/* capture opens the camera on a phone or tablet; on the counter PC it is a file picker (a saved WhatsApp photo) */}
          <input type="file" accept="image/jpeg,image/png,application/pdf" capture="environment" className={styles.rxPickerInput}
            onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        </label>
      </div>
      <label className={styles.rxPickerRow}>
        <input type="checkbox" checked={copy} onChange={(e) => setCopy(e.target.checked)} />
        <span>{needsCopy ? t("copyRequired") : t("copy")}</span>
      </label>
      {phase && <div className={styles.muted} role="status">{t(phase)}</div>}
      {error && <div className={styles.errorState} role="alert">{error}</div>}
      <div className={styles.rxPickerRow}>
        <button type="submit" className={styles.rxPickerBtn} disabled={phase != null}>{t("save")}</button>
        <button type="button" className={styles.rxPickerBtn} onClick={onCancel}>{t("cancel")}</button>
      </div>
    </form>
  );
}

class PhotoError extends Error {}
