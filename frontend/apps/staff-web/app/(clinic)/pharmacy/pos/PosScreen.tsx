"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { formatMoney } from "@nabd/i18n/format";
import { taxRegime } from "@nabd/domain";
import type { FefoPickEntry, FefoPickResponse, PharmacyItemIndexEntry, QuoteResponse, StockBatch } from "@nabd/api-client/generated";
import type { Invoice, RxLine } from "@nabd/api-client/types";
import { api, ApiError, errorText, intentKeys } from "../../../lib/api";
import { can } from "../../../lib/permissions";
import { getIdentity } from "../../../lib/session";
import { classBadge } from "../../../lib/pharmacyRules";
import { usePaymentMethodLabel } from "../../../lib/paymentMethodText";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/StateViews";
import StatusPill from "../../../components/ui/StatusPill";
import { stockErrorMessage } from "../../../components/ui/StockPill";
import RxLinkPicker from "../../../components/checkout/RxLinkPicker";
import PaperRxDialog, { type PaperRxLink } from "../../../components/checkout/PaperRxDialog";
import ScheduleAckDialog from "../../../components/checkout/ScheduleAckDialog";
import { needsAck, pharmacistWarning, type CounterRules } from "../../../components/checkout/scheduleAck";
import TenderRows from "../../../components/checkout/TenderRows";
import { rxErrorMessage } from "../../../components/checkout/rxLink";
import type { Tender } from "../../../components/checkout/tender";
// the pure module, not BarcodeWedge.tsx: the two names differ only in case, which a case-insensitive disk cannot tell apart
import { registerScanTarget } from "../../../shell/barcodeWedge";
import CameraScan, { cameraScanSupported } from "./CameraScan";
import { buildIndex, byBarcode, searchIndex, type PosIndex, type PosItem } from "./posIndex";
import { addItem, batchLabels, needsRx, saleBody, setQty, tenderSettles, type CartLine } from "./posCart";
import co from "../../checkout/checkout.module.css";
import styles from "./pos.module.css";

// PHL-019: fast counter sale. Keyboard first: F2 search, Enter adds the highlighted item, + / - change the selected line,
// F9 opens payment (cash for the full amount is prefilled), Enter completes the sale and prints the 80 mm receipt.
// A barcode adds its item: typed by a keyboard-wedge scanner (into the search box, or anywhere through the shell's
// BarcodeWedge) or read by the camera. Prices, tax, the first-expiry batch and the prescription rule are the server's:
// the total comes from POST /billing/quotes, the batches from POST /pharmacy/picks, the sale is one POST /billing/otc-checkout.
// PHL-023: GET /pharmacy/counter-rules (pharmacy:dispense, like the index) says which schedule classes need the seller's second
// confirmation before payment and whether the "no registered pharmacist on duty" banner shows; an OTC line asks for nothing.

type Note = { text: string; bad: boolean };
type ForCart<T> = { key: string; value: T };

export default function PosScreen() {
  const t = useTranslations("checkout.pos");
  const methodLabel = usePaymentMethodLabel();
  const region = getIdentity()?.tenantRegion;
  const canView = can("billing:view"); // the quote (server total) and the receipt
  // payments sent with the sale need billing:edit and the server total; without either the bill is created unpaid
  const canPay = can("billing:edit") && canView;
  const canPaperRx = can("pharmacy:dispense"); // PHL-020: every /pharmacy/paper-prescriptions call needs it; absent otherwise

  const [index, setIndex] = useState<PosIndex | null>(null);
  const [rules, setRules] = useState<CounterRules | null>(null);
  const [lastAck, setLastAck] = useState(""); // the seller's last confirmation reason: prefilled, so a repeat sale is one Enter
  const [loadError, setLoadError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [hi, setHi] = useState(0);
  const [cart, setCart] = useState<CartLine[]>([]);
  const [sel, setSel] = useState<string | null>(null);
  const [note, setNote] = useState<Note | null>(null);
  const [quote, setQuote] = useState<ForCart<QuoteResponse> | null>(null);
  const [quoteError, setQuoteError] = useState<ForCart<string> | null>(null);
  const [picks, setPicks] = useState<ForCart<Record<string, FefoPickEntry[]>> | null>(null);
  const [pickError, setPickError] = useState<ForCart<string> | null>(null);
  const [batches, setBatches] = useState<Record<string, StockBatch[]>>({});
  const [linking, setLinking] = useState<string | null>(null);
  const [paperFor, setPaperFor] = useState<string | null>(null); // the line whose "Paper Rx" dialog is open
  const [paying, setPaying] = useState(false);
  const [tenders, setTenders] = useState<Tender[] | null>(null); // null = untouched: cash for the full amount
  const [busy, setBusy] = useState(false);
  const [saleError, setSaleError] = useState<string | null>(null);
  const [invoice, setInvoice] = useState<Invoice | null>(null);
  const [receiptUrl, setReceiptUrl] = useState<string | null>(null);
  const [receiptFailed, setReceiptFailed] = useState(false);
  const [camera, setCamera] = useState(false);
  const searchRef = useRef<HTMLInputElement>(null);
  const payRef = useRef<HTMLButtonElement>(null);
  const frameRef = useRef<HTMLIFrameElement>(null);
  const keys = useRef(intentKeys()); // one Idempotency-Key per sale: a retry after a timeout cannot sell twice

  const load = useCallback(async () => {
    setLoadError(null);
    try {
      const [rows, counter] = await Promise.all([api.get<PharmacyItemIndexEntry[]>("/pharmacy/items/index"), api.get<CounterRules>("/pharmacy/counter-rules")]);
      setRules(counter);
      setIndex(buildIndex(rows));
    } catch (e) {
      setLoadError(errorText(e, t("loadFailed")));
    }
  }, [t]);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);
  useEffect(() => () => { if (receiptUrl) URL.revokeObjectURL(receiptUrl); }, [receiptUrl]);

  const results = useMemo(() => (index ? searchIndex(index, query) : []), [index, query]);
  const cartKey = useMemo(() => JSON.stringify(cart.map((l) => [l.item.code, l.qty])), [cart]);

  // Server totals and first-expiry batches for the cart as it stands; an answer for an older cart is never shown.
  useEffect(() => {
    if (cart.length === 0) return;
    const ctl = new AbortController();
    const timer = setTimeout(() => {
      if (canView) {
        api.post<QuoteResponse>("/billing/quotes", { lines: cart.map((l) => ({ chargeCode: l.item.code, quantity: l.qty })) }, { signal: ctl.signal })
          .then((q) => setQuote({ key: cartKey, value: q }))
          .catch((e) => { if (!ctl.signal.aborted) setQuoteError({ key: cartKey, value: e instanceof ApiError ? stockErrorMessage(e.problem, e.detail || t("quoteFailed")) : errorText(e, t("quoteFailed")) }); });
      }
      api.post<FefoPickResponse>("/pharmacy/picks", { lines: cart.map((l) => ({ itemId: l.item.id, qty: l.qty })) }, { signal: ctl.signal })
        .then((r) => setPicks({ key: cartKey, value: Object.fromEntries((r.lines ?? []).map((l) => [l.itemId ?? "", l.picks ?? []])) }))
        // 409 insufficient-stock / 422 batch-expired: the sale would be refused the same way, so say it now
        .catch((e) => { if (!ctl.signal.aborted && e instanceof ApiError && (e.status === 409 || e.status === 422)) setPickError({ key: cartKey, value: e.detail || t("stockChanged") }); });
    }, 120);
    return () => { clearTimeout(timer); ctl.abort(); };
  }, [cart, cartKey, canView, t]);

  const totals = cart.length > 0 && quote?.key === cartKey ? quote.value.totals ?? null : null;
  const currency = quote?.value.currency ?? invoice?.currency ?? "INR";
  const totalMinor = totals?.totalMinor ?? 0;
  const quoteProblem = cart.length > 0 && quoteError?.key === cartKey ? quoteError.value : null;
  const stockProblem = cart.length > 0 && pickError?.key === cartKey ? pickError.value : null;
  const linePicks = (l: CartLine) => (picks?.key === cartKey ? batchLabels(picks.value[l.item.id] ?? [], batches[l.item.id] ?? []) : []);
  const rows: Tender[] = tenders ?? [{ id: 1, method: "cash", amount: (totalMinor / 100).toFixed(2) }];
  const waitingRx = cart.some(needsRx);
  const waitingAck = !!rules && cart.some((l) => needsAck(l, rules)); // PHL-023
  const money = (minor: number | undefined) => formatMoney(minor ?? 0, currency, { minor: true });

  function add(item: PosItem) {
    const r = addItem(cart, item);
    setCart(r.cart);
    setQuery("");
    setHi(0);
    setNote(r.added ? { text: t("added", { name: item.name }), bad: false }
      : { text: item.onHand <= 0 ? t("outOfStock", { name: item.name }) : t("noMoreStock", { name: item.name, qty: item.onHand }), bad: true });
    if (!r.added) return;
    setSel(item.code);
    setTenders(null);
    setSaleError(null);
    if (!batches[item.id]) { // batch numbers for the pick labels: once per item and sale, failure only costs the label
      api.get<StockBatch[]>(`/pharmacy/items/${item.id}/batches`).then((b) => setBatches((prev) => ({ ...prev, [item.id]: b }))).catch(() => {});
    }
    if (item.isRx && needsRx(r.cart.find((l) => l.item.code === item.code) ?? { item, qty: 1 })) setLinking(item.code);
  }

  function scan(code: string) {
    if (!index || invoice) return;
    const item = byBarcode(index, code);
    if (item) add(item);
    else setNote({ text: t("unknownBarcode", { code: code.slice(0, 20) }), bad: true });
  }
  // a wedge scan made outside a field (the pharmacist shell mounts the listener) lands here while the screen is mounted
  const scanRef = useRef(scan);
  useEffect(() => { scanRef.current = scan; });
  useEffect(() => registerScanTarget((code) => scanRef.current(code)), []);

  function changeQty(code: string, qty: number) {
    setCart(setQty(cart, code, qty));
    setTenders(null);
    setSaleError(null);
    if (qty <= 0) {
      if (linking === code) setLinking(null);
      if (paperFor === code) setPaperFor(null);
      if (sel === code) setSel(cart.find((l) => l.item.code !== code)?.item.code ?? null);
    }
  }

  const patchLine = (code: string, patch: Partial<CartLine>) => setCart((prev) => prev.map((l) => (l.item.code === code ? { ...l, ...patch } : l)));
  function link(code: string, line: RxLine) {
    patchLine(code, { prescriptionItemId: line.prescriptionItemId, rxLabel: t("rxLinked", { drug: line.drugName, patient: line.patientName, left: line.remainingQty }) });
    setLinking(null);
    searchRef.current?.focus();
  }
  function ack(code: string, reason: string) {
    patchLine(code, { ackReason: reason });
    setLastAck(reason);
    searchRef.current?.focus();
  }
  function linkPaper(code: string, paper: PaperRxLink) {
    patchLine(code, { paperRxLineId: paper.paperRxLineId, paperLabel: t("paperRxLinked", { doctor: paper.prescriberName, qty: paper.qty }) });
    setPaperFor(null);
    searchRef.current?.focus();
  }

  async function showReceipt(id: string) {
    if (!canView) return;
    try {
      const pdf = await api.get<Blob>(`/billing/invoices/${id}/document?layout=thermal80&kind=invoice`, { as: "blob" });
      setReceiptUrl(URL.createObjectURL(pdf)); // the frame prints itself once the PDF is in it
    } catch {
      setReceiptFailed(true);
    }
  }

  const printReceipt = () => {
    try { frameRef.current?.contentWindow?.print(); } catch { /* a browser that will not print a framed PDF: the "Open receipt" link stays */ }
  };

  async function completeSale() {
    if (busy || cart.length === 0 || waitingRx || waitingAck) return;
    if (canPay && !tenderSettles(rows, totalMinor)) {
      setSaleError(totals ? t("tenderMismatch", { total: money(totalMinor) }) : t("totalPending"));
      return;
    }
    setBusy(true);
    setSaleError(null);
    const body = saleBody(cart, canPay ? rows : undefined);
    try {
      const inv = await api.post<Invoice>("/billing/otc-checkout", body, { idempotencyKey: keys.current.get("sale", body) });
      keys.current.done("sale");
      setInvoice(inv);
      setCart([]);
      setPaying(false);
      setTenders(null);
      setLinking(null);
      setPaperFor(null);
      setSel(null);
      setNote(null);
      setBatches({});
      void showReceipt(inv.id);
      void load(); // stock on hand moved
    } catch (e) {
      setSaleError(e instanceof ApiError ? rxErrorMessage(e.problem) ?? stockErrorMessage(e.problem, e.detail || t("saleFailed")) : errorText(e, t("saleFailed")));
    } finally {
      setBusy(false);
    }
  }

  function newSale() {
    setInvoice(null);
    setReceiptUrl(null);
    setReceiptFailed(false);
    setSaleError(null);
    setTimeout(() => searchRef.current?.focus(), 0);
  }

  function pay() {
    if (invoice || cart.length === 0 || waitingRx || waitingAck) return;
    if (paying || !canPay) void completeSale();
    else setPaying(true);
  }

  // Screen keys. Declared without dependencies on purpose: it always sees the current cart.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "F2") { e.preventDefault(); if (invoice) newSale(); else searchRef.current?.focus(); }
      else if (e.key === "F9") { e.preventDefault(); pay(); }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  });
  useEffect(() => { if (paying && (totals || !canView)) payRef.current?.focus(); }, [paying, totals, canView]);

  function onSearchKey(e: React.KeyboardEvent<HTMLInputElement>) {
    const line = cart.find((l) => l.item.code === sel);
    if (e.key === "Enter") {
      e.preventDefault();
      const pick = results[hi];
      if (pick) add(pick);
      else if (/^\d{7,}$/.test(query.trim())) { setNote({ text: t("unknownBarcode", { code: query.trim().slice(0, 20) }), bad: true }); setQuery(""); }
    } else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      const step = e.key === "ArrowDown" ? 1 : -1;
      if (results.length > 0) setHi((hi + step + results.length) % results.length);
      else if (cart.length > 0) { // empty search box: the arrows walk the cart
        const at = cart.findIndex((l) => l.item.code === sel);
        setSel(cart[(at + step + cart.length) % cart.length].item.code);
      }
    } else if (query === "" && line && (e.key === "+" || e.key === "=" || e.key === "-")) {
      e.preventDefault();
      changeQty(line.item.code, line.qty + (e.key === "-" ? -1 : 1));
    } else if (e.key === "Escape" && query) {
      setQuery("");
    }
  }

  if (loadError && !index) return <main className={co.page}><ErrorState message={loadError} onRetry={() => void load()} /></main>;
  if (!index || !rules) return <main className={co.page}><Skeleton rows={6} label={t("loading")} /></main>;

  const header = (
    <div className={co.strip}>
      <div className={co.stripIdentity}>
        <h1 className={co.stripName}>{t("title")}</h1>
        <span className={co.stripMeta}>{t("meta")}</span>
      </div>
      <Link className={co.backBtn} href="/checkout/otc">{t("fullCounterSale")}</Link>
    </div>
  );

  if (invoice) {
    return (
      <main className={co.page}>
        {header}
        <div className={styles.done}>
          <section className={co.card} aria-label={t("saleDone")}>
            <h2 className={co.cardTitle}>{t("saleDone")}</h2>
            <p className={styles.bigTotal} data-testid="pos-invoice-number">{invoice.invoiceNumber}</p>
            <div className={co.totals}>
              <div className={co.totalsRowMain}><span>{t("total")}</span><span>{formatMoney(invoice.total, invoice.currency)}</span></div>
              {invoice.payments.map((p) => (
                <div key={p.id} className={co.totalsRow} data-testid="pos-payment-row"><span>{methodLabel(p.method, region)}</span><span>{formatMoney(p.amount, invoice.currency)}</span></div>
              ))}
              <div className={co.totalsRow}><span>{t("balance")}</span><span data-testid="pos-balance">{formatMoney(invoice.balanceDue, invoice.currency)}</span></div>
            </div>
            {invoice.balanceDue > 0 && <p className={styles.hint} role="status">{t("unpaidHint")}</p>}
            <div className={styles.actions}>
              <button type="button" className={co.checkoutBtn} autoFocus onClick={newSale} aria-keyshortcuts="F2">{t("newSale")} <kbd className={styles.kbd}>F2</kbd></button>
              {receiptUrl && <button type="button" className={co.backBtn} onClick={printReceipt}>{t("printReceipt")}</button>}
              {receiptUrl && <a className={co.backBtn} href={receiptUrl} target="_blank" rel="noreferrer">{t("openReceipt")}</a>}
            </div>
            {receiptFailed && <p className={co.errorState} role="alert">{t("receiptFailed")}</p>}
          </section>
          {canView && (
            <section className={co.card} aria-label={t("receipt")}>
              <h2 className={co.cardTitle}>{t("receipt")}</h2>
              {receiptUrl
                ? <iframe ref={frameRef} className={styles.receipt} src={receiptUrl} title={t("receipt")} onLoad={printReceipt} />
                : !receiptFailed && <Skeleton rows={5} label={t("receiptLoading")} />}
            </section>
          )}
        </div>
      </main>
    );
  }

  return (
    <main className={co.page}>
      {header}
      {loadError && <p className={co.errorState} role="alert">{loadError}</p>}
      {/* PHL-023: stays for as long as the cart holds a line of a class that warns; the sale is not blocked, the server audits it */}
      {pharmacistWarning(cart, rules) && <p className={co.errorState} role="alert" data-testid="pos-no-pharmacist">{t("noPharmacist")}</p>}
      <div className={styles.layout}>
        <section className={co.card} aria-label={t("searchLabel")}>
          <div className={styles.searchRow}>
            <input ref={searchRef} className={styles.search} type="search" autoFocus autoComplete="off" spellCheck={false} value={query}
              role="combobox" aria-expanded={results.length > 0} aria-controls="pos-results" aria-autocomplete="list"
              aria-activedescendant={results[hi] ? `pos-r-${results[hi].code}` : undefined} aria-keyshortcuts="F2"
              aria-label={t("searchLabel")} placeholder={t("searchPlaceholder")}
              onChange={(e) => { setQuery(e.target.value); setHi(0); }} onKeyDown={onSearchKey} />
            {cameraScanSupported() && <button type="button" className={co.backBtn} onClick={() => setCamera(true)}>{t("camera")}</button>}
          </div>
          <p className={styles.hint}>{t("keys")}</p>
          <ul id="pos-results" role="listbox" aria-label={t("results")} className={styles.results}>
            {results.map((r, i) => (
              <li key={r.code} id={`pos-r-${r.code}`} role="option" aria-selected={i === hi} aria-disabled={r.onHand <= 0}
                className={`${styles.result} ${i === hi ? styles.resultOn : ""}`} onMouseEnter={() => setHi(i)} onClick={() => { add(r); searchRef.current?.focus(); }}>
                <span className={styles.resultName}>
                  <bdi>{r.name}</bdi> {r.isRx && <StatusPill tone="danger" icon="alert" label={classBadge(r.scheduleClass)} />}
                  {r.generic && <span className={co.muted}> · <bdi>{r.generic}</bdi></span>}
                </span>
                <span className={styles.resultMeta}>
                  {r.onHand <= 0 ? t("out") : t("onHand", { qty: r.onHand })} · {formatMoney(r.price, currency)}
                </span>
              </li>
            ))}
          </ul>
          {index.items.length === 0 && <EmptyState icon="inbox" title={t("noItems")} hint={t("noItemsHint")} />}
          {index.items.length > 0 && query.trim() !== "" && results.length === 0 && <p className={styles.hint} role="status">{t("noMatch")}</p>}
          <p className={note?.bad ? co.errorState : styles.hint} role="status" aria-live="polite" data-testid="pos-note">{note?.text ?? ""}</p>
        </section>

        <section className={co.card} aria-label={t("cart")}>
          <h2 className={co.cardTitle}>{t("cart")}</h2>
          {cart.length === 0 ? <p className={co.muted}>{t("cartEmpty")}</p> : (
            <table className={co.table}>
              <thead><tr><th scope="col">{t("colItem")}</th><th scope="col">{t("colQty")}</th><th scope="col">{t("colPrice")}</th><th scope="col"><span className={styles.srOnly}>{t("remove")}</span></th></tr></thead>
              <tbody>
                {cart.map((l) => {
                  const code = l.item.code;
                  return (
                    <tr key={code} className={sel === code ? styles.lineOn : undefined} aria-current={sel === code ? "true" : undefined} onClick={() => setSel(code)}>
                      <td>
                        <bdi>{l.item.name}</bdi> {l.item.isRx && <StatusPill tone="danger" icon="alert" label={classBadge(l.item.scheduleClass)} />}
                        {linePicks(l).map((b) => <div key={b} className={co.muted} data-testid={`pos-batch-${code}`}>{t("batch", { batch: b })}</div>)}
                        {l.rxLabel && <div className={co.muted}>{l.rxLabel}</div>}
                        {l.paperLabel && <div className={co.muted} data-testid={`paper-rx-linked-${code}`}>{l.paperLabel}</div>}
                        {needsRx(l) && linking !== code && <button type="button" className={co.rxLinkBtn} onClick={() => { setPaperFor(null); setLinking(code); }}>{t("linkRx")}</button>}
                        {/* PHL-020: the paper / photo prescription path; absent without pharmacy:dispense */}
                        {canPaperRx && needsRx(l) && paperFor !== code && <button type="button" className={co.rxLinkBtn} data-testid={`paper-rx-${code}`} onClick={() => { setLinking(null); setPaperFor(code); }}>{t("paperRx")}</button>}
                        {linking === code && <RxLinkPicker itemName={l.item.name} onPick={(line) => link(code, line)} onCancel={() => setLinking(null)} />}
                        {paperFor === code && <PaperRxDialog item={{ id: l.item.id, name: l.item.name }} quantity={l.qty} onLinked={(p) => linkPaper(code, p)} onCancel={() => setPaperFor(null)} />}
                        {l.ackReason && <div className={co.muted} data-testid={`ack-done-${code}`}>{t("ackDone", { reason: l.ackReason })}</div>}
                        {/* PHL-023: the second confirmation, once no prescription panel is open on the line; payment stays off until it is given */}
                        {needsAck(l, rules) && linking !== code && paperFor !== code && <ScheduleAckDialog itemName={l.item.name} initial={lastAck} onConfirm={(reason) => ack(code, reason)} />}
                      </td>
                      <td>
                        <span className={co.qtyStepper}>
                          <button type="button" className={co.stepBtn} aria-label={t("less", { name: l.item.name })} onClick={() => changeQty(code, l.qty - 1)}>−</button>
                          <span data-testid={`pos-qty-${code}`}>{l.qty}</span>
                          {l.qty < l.item.onHand && <button type="button" className={co.stepBtn} aria-label={t("more", { name: l.item.name })} onClick={() => changeQty(code, l.qty + 1)}>+</button>}
                        </span>
                      </td>
                      <td>{formatMoney(l.item.price * l.qty, currency)}</td>
                      <td><button type="button" className={co.removeBtn} onClick={() => changeQty(code, 0)}>{t("remove")}</button></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
          {stockProblem && <p className={co.errorState} role="alert">{stockProblem}</p>}
        </section>

        <section className={co.card} aria-label={t("payment")}>
          <div className={co.totals}>
            <div className={co.totalsRow}><span>{t("subtotal")}</span><span>{totals ? money(totals.subtotalMinor) : "—"}</span></div>
            <div className={co.totalsRow}><span>{taxRegime(region).label}</span><span>{totals ? money(totals.taxMinor) : "—"}</span></div>
            {!!totals?.roundOffMinor && <div className={co.totalsRow}><span>{t("roundOff")}</span><span>{money(totals.roundOffMinor)}</span></div>}
            <div className={co.totalsRowMain}><span>{t("total")}</span><span data-testid="pos-total">{totals ? money(totalMinor) : "—"}</span></div>
          </div>
          {quoteProblem && <p className={co.errorState} role="alert">{quoteProblem}</p>}
          {waitingRx && <p className={co.errorState} role="status" data-testid="pos-rx-wait">{t("rxWait")}</p>}
          {waitingAck && !waitingRx && <p className={co.errorState} role="status" data-testid="pos-ack-wait">{t("ackWait")}</p>}
          {paying && canPay && totals && (
            <TenderRows rows={rows} onChange={setTenders} region={region} balanceMinor={totalMinor} disabled={busy} />
          )}
          {paying && canPay && !totals && !quoteProblem && <p className={styles.hint} role="status">{t("totalPending")}</p>}
          {saleError && <p className={co.errorState} role="alert">{saleError}</p>}
          {cart.length > 0 && !waitingRx && !waitingAck && (
            <button ref={payRef} type="button" className={co.checkoutBtn} aria-keyshortcuts="F9" disabled={busy} onClick={pay}>
              {busy ? t("selling") : !canPay ? t("createBill") : paying ? t("completeSale") : t("pay")} <kbd className={styles.kbd}>F9</kbd>
            </button>
          )}
          {!canPay && cart.length > 0 && <p className={styles.hint}>{t("createBillHint")}</p>}
        </section>
      </div>
      {camera && <CameraScan onScan={scan} onClose={() => setCamera(false)} status={note?.text ?? null} />}
    </main>
  );
}
