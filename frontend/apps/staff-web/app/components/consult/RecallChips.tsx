"use client";

// COM-052: "Review in 2 weeks" at consult close. One tap (or one Enter/Space on a focused chip) creates the recall order
// (POST /encounters/{id}/recall-orders, due = visit date + days on the clinic calendar); no confirm step. Booking the
// follow-up marks it booked on the server. Lives in the consult (follow-up panel), not in a shell, so every layout gets it.
import { useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";
import { api, ApiError, errorText, intentKeys } from "../../lib/api";
import StatusPill from "../ui/StatusPill";
import { Skeleton } from "../ui/StateViews";
import { MAX_RECALL_DAYS, RECALL_CHIPS, recallLabel, validRecallDays, type Recall } from "./recall";
import s from "./consultParts.module.css";

export default function RecallChips({ entryId, canSet, canView, onChange, refresh }: {
  entryId: string;
  /** Changes when a follow-up was booked here: the server then marks the recall booked, so read it again. */
  refresh?: string | null;
  /** Visit doctor with clinical:edit; the chips are absent for everyone else. */
  canSet: boolean;
  /** clinical:view: without it nothing is read or shown. */
  canView: boolean;
  onChange: (r: Recall | null | undefined) => void;
}) {
  const tx = useTranslations("consult.recallChips");
  const [recall, setRecall] = useState<Recall | null | undefined>(undefined);
  const [busy, setBusy] = useState<string | null>(null);
  const [custom, setCustom] = useState("");
  const [error, setError] = useState<string | null>(null);
  const keys = useRef(intentKeys());
  const report = useRef(onChange);
  useEffect(() => { report.current = onChange; });

  const show = (r: Recall | null | undefined) => { setRecall(r); report.current(r); };

  async function load() {
    try {
      const list = await api.get<Recall[]>(`/encounters/${entryId}/recall-orders`);
      show(list[0] ?? null);
    } catch {
      show(undefined);
      setError(tx("couldNotLoad"));
    }
  }

  useEffect(() => {
    if (!canView) return;
    const t = setTimeout(() => void load(), 0);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once per visit
  }, [entryId, canView, refresh]);

  async function set(id: string, days: number) {
    if (!validRecallDays(days)) { setError(tx("outOfRange", { max: MAX_RECALL_DAYS })); return; }
    setBusy(id);
    setError(null);
    const body = { intervalDays: days };
    try {
      show(await api.post<Recall>(`/encounters/${entryId}/recall-orders`, body, { idempotencyKey: keys.current.get(`recall-${entryId}`, body) }));
    } catch (e) {
      if (e instanceof ApiError && e.problem?.code === "RECALL_EXISTS_FOR_ENCOUNTER") await load(); // set in another tab: show it
      else setError(errorText(e, tx("couldNotSet")));
    } finally {
      setBusy(null);
    }
  }

  if (!canView) return null;
  return (
    <div className={s.mtM} role="group" aria-label={tx("recall")}>
      <p className={s.muted}>
        <strong>{tx("recall")}</strong>{" "}
        {recall === undefined && !error ? null
          : recall ? <StatusPill tone="ok" icon="calendar" label={tx(recall.status === "booked" ? "dueBooked" : "duePending", { date: recallLabel(recall) ?? "" })} />
          : recall === null ? <StatusPill tone="neutral" icon="calendar" label={tx("notSet")} /> : null}
      </p>
      {recall === undefined && !error && <Skeleton rows={1} label={tx("loading")} />}
      {canSet && recall === null && (
        <>
          <div className={s.row} role="group" aria-label={tx("reviewIn")}>
            {RECALL_CHIPS.map((c) => (
              <button key={c.id} type="button" className={`${s.chip} ${s.recallChip}`} disabled={busy !== null} onClick={() => void set(c.id, c.days)}>
                {tx(`chip.${c.id}`)}
              </button>
            ))}
          </div>
          <form className={`${s.row} ${s.mtS}`} onSubmit={(e) => { e.preventDefault(); void set("custom", Number(custom)); }}>
            <label className={s.muted} htmlFor={`recallDays-${entryId}`}>{tx("customDays")}</label>
            <input id={`recallDays-${entryId}`} type="number" inputMode="numeric" min={1} max={MAX_RECALL_DAYS} className={s.input}
              value={custom} onChange={(e) => setCustom(e.target.value)} />
            <button type="submit" className={`${s.chip} ${s.recallChip}`} disabled={busy !== null || !custom}>{tx("setCustom")}</button>
          </form>
        </>
      )}
      {busy && <p className={s.muted} role="status">{tx("saving")}</p>}
      {recall && <p className={`${s.muted} ${s.mtS}`}>{tx(recall.status === "booked" ? "noReminderBooked" : "reminderNote")}</p>}
      {error && <div className={`${s.notice} ${s.noticeDanger} ${s.mtS}`} role="alert">{error}{recall === undefined && <> <button type="button" className={s.linkBtn} onClick={() => { setError(null); void load(); }}>{tx("retry")}</button></>}</div>}
    </div>
  );
}
