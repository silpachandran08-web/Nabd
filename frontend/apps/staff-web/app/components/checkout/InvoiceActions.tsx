"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { api, ApiError, intentKeys } from "../../lib/api";
import { can } from "../../lib/permissions";
import styles from "../../(clinic)/checkout/checkout.module.css";

// FEF-047: after a payment, the cashier prints the receipt (Enter), prints the invoice, or sends the PDF link on WhatsApp.
// Print opens the print route (which prints as soon as the document is on screen). Share calls POST /billing/invoices/{id}/share
// (Core billing, FIN-012): the patient gets a secure link, never a file in chat.
export default function InvoiceActions({ invoiceId, reprint = false }: { invoiceId: string; reprint?: boolean }) {
  const tr = useTranslations("checkout.invoiceActions");
  const keys = useRef(intentKeys());
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const copy = reprint ? "&copy=duplicate" : "";
  const base = `/billing/invoices/${encodeURIComponent(invoiceId)}/print`;
  const receiptHref = `${base}?kind=receipt&auto=1${copy}`;
  const receiptLink = useRef<HTMLAnchorElement>(null);

  useEffect(() => {
    // Enter on the success state prints the receipt; any focused control keeps its own Enter.
    const onKey = (e: KeyboardEvent) => { if (e.key === "Enter" && (e.target === document.body || e.target === null)) receiptLink.current?.click(); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  const send = async () => {
    setBusy(true); setMsg(null);
    const body = { channel: "whatsapp", recipient: "patient" };
    try {
      await api.post(`/billing/invoices/${encodeURIComponent(invoiceId)}/share`, body, { idempotencyKey: keys.current.get("share", body) });
      keys.current.done("share");
      setMsg(tr("sent"));
    } catch (e) {
      const t = e instanceof ApiError ? String(e.problem?.type ?? "") : "";
      setMsg(t.endsWith("no-messaging-consent") ? tr("noConsent") : t.endsWith("no-phone") ? tr("noPhone") : tr("sendFailed"));
    } finally { setBusy(false); }
  };

  return (
    <div className={styles.docActions}>
      <Link ref={receiptLink} className={styles.backBtn} href={receiptHref}>{tr("printReceipt")}</Link>
      <Link className={styles.backBtn} href={`${base}?kind=invoice&auto=1${copy}`}>{tr("printInvoice")}</Link>
      {can("billing:edit") && <button type="button" onClick={() => void send()} disabled={busy}>{busy ? tr("sending") : tr("sendWhatsapp")}</button>}
      {msg && <div role="status" className={styles.muted}>{msg}</div>}
    </div>
  );
}
