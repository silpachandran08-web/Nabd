"use client";

import { useTranslations } from "next-intl";
import { formatMoney } from "@nabd/domain";
import { fromMinor, hasPendingDecision, sharesByLine, splitSummary, type InvoiceInsurance } from "../../lib/insurance";
import styles from "./insurance.module.css";

type Line = { id?: string | null; chargeCode?: string; chargeName?: string };

/** FIN-036: the patient / insurer split of an insured invoice, line by line. The desk collects only the patient's share (the invoice's
 * balance due already is that); what the insurer left unpaid waits for a decision and is shown apart, never added to the patient. */
export default function InsuranceSplit({ insurance, lines, currency }: { insurance: InvoiceInsurance; lines: Line[]; currency: string }) {
  const t = useTranslations("common.insurance.split");
  const byLine = sharesByLine(insurance);
  const s = splitSummary(insurance);
  const money = (minor: number) => formatMoney(fromMinor(minor, currency), currency);
  return (
    <div data-testid="insurance-split">
      <table className={styles.split} aria-label={t("title")}>
        <thead>
          <tr>
            <th scope="col">{t("charge")}</th>
            <th scope="col" className={styles.num}>{t("patientPays")}</th>
            <th scope="col" className={styles.num}>{t("payerPays")}</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((l, i) => {
            const share = l.id ? byLine.get(l.id) : undefined;
            return (
              <tr key={l.id ?? l.chargeCode ?? i}>
                <td>{l.chargeName}</td>
                <td className={styles.num}>{share ? money(share.patientMinor) : "–"}</td>
                <td className={styles.num}>{share ? money(share.payerMinor) : "–"}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <div className={`${styles.sum} ${styles.sumMain}`}><span>{t("patientShare")}</span><span>{formatMoney(s.patient, currency)}</span></div>
      <div className={styles.sum}><span>{t("payerShare")}</span><span>{formatMoney(s.payer, currency)}</span></div>
      {(insurance.payerPaid ?? 0) > 0 && <div className={styles.sum}><span>{t("payerPaid")}</span><span>{formatMoney(insurance.payerPaid ?? 0, currency)}</span></div>}
      {hasPendingDecision(insurance) && (
        <div className={styles.warn} role="status">{t("pending")}: {formatMoney(insurance.pendingDecision ?? 0, currency)}</div>
      )}
    </div>
  );
}
