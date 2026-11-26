"use client";

// PHL-043: the order form of the consult's Tests card (the clinic has the lab module): typeahead over the catalogue as a client index (typing
// never waits on the server), favourite chips, priority, destination, fasting, note, Order (Ctrl+Enter). Mounted only after the entitlements answer.
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { formatNumber } from "@nabd/i18n";
import { api, intentKeys, reasonText } from "../../lib/api";
import type { LabPanel, LabTest, LabTestPage } from "../../lib/labCatalogue";
import {
  DESTINATIONS, catalogueIndex, clash, emptyOrder, fastingMessage, orderBody, orderError, orderTotal, searchCatalogue,
  type Favourite, type OrderForm, type OrderPlaced, type Orderable,
} from "../../lib/labOrders";
import Icon from "../ui/Icon";
import { ErrorState, Skeleton } from "../ui/StateViews";
import LabOrderList from "./LabOrderList";
import { Fasting, PriorityToggle } from "./LabTestsParts";
import type { Row } from "./shared";
import s from "./consultParts.module.css";
import c from "./labTests.module.css";

const money = (v: number) => formatNumber(Number(v), { min: 2, max: 2 });

export default function OrderPanel({ entry }: { entry: Row }) {
  const t = useTranslations("consult.labTests");
  const [index, setIndex] = useState<Orderable[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [favourites, setFavourites] = useState<Favourite[]>([]);
  const [form, setForm] = useState<OrderForm>(emptyOrder);
  const [term, setTerm] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [placed, setPlaced] = useState<OrderPlaced | null>(null);
  const [version, setVersion] = useState(0); // goes up when an order is placed: the visit's order list reloads
  const keys = useRef(intentKeys()); // one Idempotency-Key per order intent: a retry after a timeout reuses it

  const loadCatalogue = useCallback(async () => {
    setLoadError(false);
    try {
      const tests: LabTest[] = [];
      let cursor: string | undefined;
      for (let i = 0; i < 20; i++) { // 200 a page; the cap keeps a runaway cursor from looping
        const page = await api.get<LabTestPage>(`/lab/tests?limit=200${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`);
        tests.push(...page.items);
        cursor = page.nextCursor ?? undefined;
        if (!cursor) break;
      }
      const panels = await api.get<{ items: LabPanel[] }>("/lab/panels");
      setIndex(catalogueIndex(tests, panels.items));
    } catch { setLoadError(true); }
  }, []);
  const loadFavourites = useCallback(async () => {
    try { setFavourites((await api.get<{ items: Favourite[] }>("/lab/order-favourites")).items); } catch { setFavourites([]); } // chips are a shortcut, never a blocker
  }, []);
  useEffect(() => { void Promise.resolve().then(() => { void loadCatalogue(); void loadFavourites(); }); }, [loadCatalogue, loadFavourites]);

  const matches = useMemo(() => searchCatalogue(index ?? [], term), [index, term]);
  const chips = useMemo(() => favourites.map((f) => (index ?? []).find((o) => o.kind === f.kind && o.id === f.id)).filter((o): o is Orderable => !!o), [favourites, index]);

  function pick(o: Orderable) {
    const other = clash(form.picked, o);
    if (other) { setProblem(t("clash", { name: o.name, other: other.name })); return; }
    setProblem(null);
    setForm((f) => ({ ...f, picked: [...f.picked, o] }));
    setTerm("");
  }

  async function place() {
    const err = orderError(form);
    if (busy) return;
    if (err) { setProblem(t(`errors.${err}`)); return; }
    setBusy(true); setProblem(null);
    const body = orderBody(form, entry.patientId, entry.id);
    try {
      const r = await api.post<OrderPlaced>("/lab/orders", body, { idempotencyKey: keys.current.get("place", body) });
      keys.current.done("place");
      setPlaced(r); setForm(emptyOrder()); setTerm("");
      setVersion((v) => v + 1); void loadFavourites();
    } catch (e) { setProblem(reasonText(e, t("placeFailed"))); } finally { setBusy(false); }
  }

  if (loadError) return <ErrorState message={t("loadFailed")} onRetry={() => void loadCatalogue()} />;
  if (index === null) return <Skeleton rows={3} label={t("loading")} />;
  const fastingNote = fastingMessage(placed?.fastingNotice);
  return (
    <div onKeyDown={(e) => { if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); void place(); } }}>
      <div className={c.field}>
        <label htmlFor="lab-order-search">{t("search")}</label>
        <input id="lab-order-search" className={`${s.input} ${c.input} ${c.target}`} value={term} maxLength={60} autoComplete="off" placeholder={t("searchHint")}
          onChange={(e) => setTerm(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter" && !e.ctrlKey && !e.metaKey && matches[0]) { e.preventDefault(); e.stopPropagation(); pick(matches[0]); } }} />
      </div>
      {term.trim() && matches.length === 0 && <p className={s.muted}>{t("noMatch")}</p>}
      {matches.length > 0 && (
        <ul className={c.suggest} aria-label={t("suggestions")}>
          {matches.map((o) => (
            <li key={`${o.kind}-${o.id}`}>
              <button type="button" className={c.suggestBtn} onClick={() => pick(o)}>
                <span><strong>{o.code}</strong> {o.name} <span className={s.muted}>{t(`kind.${o.kind}`)}</span></span>
                <span className={c.num}>{money(o.price)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {chips.length > 0 && (
        <div className={`${s.row} ${c.field}`} role="group" aria-label={t("favourites")}>
          <span className={s.muted}>{t("favourites")}</span>
          {chips.map((o) => (
            <button key={`${o.kind}-${o.id}`} type="button" className={`${s.chip} ${c.target}`} onClick={() => pick(o)}>{o.name}</button>
          ))}
        </div>
      )}

      {form.picked.length > 0 && (
        <ul className={c.picked} aria-label={t("picked")}>
          {form.picked.map((o) => (
            <li key={`${o.kind}-${o.id}`}>
              <button type="button" className={`${s.chip} ${s.chipOn} ${c.target}`} aria-label={t("remove", { name: o.name })}
                onClick={() => setForm((f) => ({ ...f, picked: f.picked.filter((x) => x !== o) }))}>{o.name} <Icon name="x" size={12} /></button>
            </li>
          ))}
        </ul>
      )}

      <div className={c.field}>
        <PriorityToggle value={form.priority} onChange={(priority) => setForm((f) => ({ ...f, priority }))} />
      </div>
      <div className={c.field} role="group" aria-label={t("destination")}>
        <div className={s.row}>
          {DESTINATIONS.map((d) => (
            <button key={d} type="button" className={`${form.destination === d ? s.chipOn : ""} ${s.chip} ${c.target}`} aria-pressed={form.destination === d}
              onClick={() => setForm((f) => ({ ...f, destination: d }))}>{t(`destinations.${d}`)}</button>
          ))}
        </div>
        {form.destination === "external" && <span className={s.muted}>{t("externalHint")}</span>}
      </div>
      <div className={c.field}>
        <Fasting on={form.fasting} hours={form.fastingHours} onChange={(fasting, fastingHours) => setForm((f) => ({ ...f, fasting, fastingHours }))} />
      </div>
      <div className={c.field}>
        <label htmlFor="lab-order-note">{t("note")}</label>
        <textarea id="lab-order-note" className={c.textarea} maxLength={500} value={form.note} onChange={(e) => setForm((f) => ({ ...f, note: e.target.value }))} />
      </div>
      <div className={s.row}>
        <button type="button" className={`${s.btnPrimary} ${c.target}`} disabled={busy || form.picked.length === 0} onClick={() => void place()}>
          {busy ? t("ordering") : t("order")} {form.picked.length > 0 && form.destination === "inhouse" && <span className={c.num}>({money(orderTotal(form.picked))})</span>}
        </button>
        <span className={s.muted}>{t("shortcut")}</span>
      </div>

      {problem && <div className={`${s.notice} ${s.noticeDanger} ${s.mtS}`} role="alert"><Icon name="alert" size={16} /><span>{problem}</span></div>}
      {placed && (
        <div className={`${s.notice} ${s.noticeOk} ${s.mtS}`} role="status">
          <Icon name="check" size={16} />
          <span>
            {t("placed", { reference: placed.accessionHint })}{" "}
            {placed.charges.length > 0 ? t("charged", { total: money(placed.charges.reduce((sum, ch) => sum + Number(ch.amount), 0)) }) : t("notCharged")}
            {fastingNote && <> {t(fastingNote)}</>}
          </span>
        </div>
      )}

      <LabOrderList queueEntryId={entry.id} version={version} />
    </div>
  );
}
