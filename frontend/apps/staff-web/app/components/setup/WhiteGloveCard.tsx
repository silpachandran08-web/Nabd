"use client";
// ADM-006 owner.setup: the "Have us set it up" card in the wizard header. Shows the one-time fee from the clinic's plan (or that it
// is free on an annual plan), and the clinic's booking once made. The booking form (call slots, note, MFA code bound to
// "white-glove:book") is absent for anyone but the owner with setup:edit; the server enforces the same and one open booking.
import { useState } from "react";
import { useTranslations } from "next-intl";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { formatMoney } from "@nabd/i18n/format";
import { ErrorState, Skeleton } from "../ui/StateViews";
import { api, reasonText } from "../../lib/api";
import { getter, key } from "../../lib/query";
import { can } from "../../lib/permissions";
import { isOwner } from "../../lib/session";
import { requestStepUpToken } from "../../lib/stepUp";
import { useHasMounted } from "../../lib/useHasMounted";
import { isOpen, MAX_SLOTS, slotsToIso, WHITE_GLOVE_STEP_UP, type Booked, type OwnerView } from "./whiteGlove";
import styles from "./whiteGlove.module.css";

const money = (minor: number, currency: string) => formatMoney(minor, currency, { minor: true });

export default function WhiteGloveCard() {
  const t = useTranslations("billing.whiteGlove");
  const qc = useQueryClient();
  const mounted = useHasMounted();
  const canBook = mounted && isOwner() && can("setup:edit");
  const q = useQuery({ queryKey: key("white-glove"), queryFn: getter<OwnerView>("/setup/white-glove") });
  const [slots, setSlots] = useState<string[]>(Array(MAX_SLOTS).fill(""));
  const [notes, setNotes] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [booked, setBooked] = useState<Booked | null>(null);

  if (q.isPending) return <Skeleton rows={2} label={t("loading")} />;
  if (q.isError) return <ErrorState message={t("loadFailed")} onRetry={() => void q.refetch()} />;
  const { offer, engagement } = q.data;
  const iso = slotsToIso(slots);
  const ready = !!iso && /^\d{6}$/.test(code) && !busy;

  async function book() {
    if (!iso || busy) return;
    setBusy(true);
    setError(null);
    const su = await requestStepUpToken(code, WHITE_GLOVE_STEP_UP);
    if ("error" in su) { setError(su.error); setBusy(false); return; }
    try {
      const r = await api.post<Booked>("/setup/white-glove", { preferredSlots: iso, ...(notes.trim() ? { notes: notes.trim() } : {}) },
        { headers: { "X-Step-Up-Token": su.token } });
      setBooked(r);
      await qc.invalidateQueries({ queryKey: key("white-glove") });
    } catch (e) {
      setError(reasonText(e, t("bookFailed")));
      setCode("");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className={styles.card} aria-labelledby="white-glove-h" data-testid="white-glove-card">
      <h2 id="white-glove-h" className={styles.h3}>{t("title")}</h2>
      <p className={styles.muted}>{t("lead")}</p>
      {booked && <div className={styles.notice} role="status">{t("booked")}</div>}
      {engagement && engagement.status !== "cancelled" ? (
        <div className={styles.row} data-testid="white-glove-status">
          <span>{t("statusLine", { status: t(`status.${engagement.status}`) })}</span>
          {engagement.slotAt && <span className={styles.muted}>{t("slotAt", { time: new Date(engagement.slotAt).toLocaleString() })}</span>}
          {engagement.status === "done" && engagement.coveredSteps.length > 0 && (
            <span className={styles.muted}>{t("covered", { steps: engagement.coveredSteps.join(", ") })}</span>
          )}
          {engagement.fee.status === "due" && isOpen(engagement) && (
            booked?.paymentLink
              ? <a className={styles.link} href={booked.paymentLink} rel="noopener noreferrer" target="_blank">{t("paymentLink")}</a>
              : <span className={styles.muted}>{t("paymentPending")}</span>
          )}
        </div>
      ) : !offer ? (
        <p className={styles.muted}>{t("notOffered")}</p>
      ) : (
        <>
          <p data-testid="white-glove-fee"><strong>{offer.feeStatus === "waived" ? t("waived") : t("fee", { amount: money(offer.feeMinor, offer.currency) })}</strong></p>
          {canBook ? (
            <div className={styles.dialog}>
              <fieldset className={styles.fieldset}>
                <legend>{t("slots")}</legend>
                {slots.map((v, i) => (
                  <label key={i} className={styles.field}>
                    <span>{t("slot", { n: i + 1 })}</span>
                    <input className={styles.input} type="datetime-local" value={v}
                      onChange={(e) => setSlots((s) => s.map((x, j) => (j === i ? e.target.value : x)))} />
                  </label>
                ))}
              </fieldset>
              <label className={styles.field}>
                <span>{t("notes")}</span>
                <input className={styles.input} maxLength={500} value={notes} onChange={(e) => setNotes(e.target.value)} />
              </label>
              <label className={styles.field}>
                <span>{t("mfaCode")}</span>
                <input className={styles.input} inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code}
                  onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} />
              </label>
              <p className={styles.muted}>{t("grantNote")}</p>
              {error && <div className={styles.error} role="alert">{error}</div>}
              <div className={styles.actions}>
                <button type="button" className={styles.btn} disabled={!ready} onClick={() => void book()}>{busy ? t("booking") : t("book")}</button>
              </div>
            </div>
          ) : mounted && <p className={styles.muted}>{t("ownerOnly")}</p>}
        </>
      )}
    </section>
  );
}
