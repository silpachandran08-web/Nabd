"use client";
// COM-015 owner.plan: messages sent and what they cost, by channel and pricing category, for one calendar month. With a plan
// allowance the WhatsApp count is shown against it (bar, and the 80% / 100% alert the server raised); without one only the cost is
// shown. The section is absent without setup:view (the server enforces the same). Amounts are the provider's cost.
import { useState } from "react";
import { useTranslations } from "next-intl";
import { useQuery } from "@tanstack/react-query";
import { formatMoney } from "@nabd/i18n/format";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { reasonText } from "../../lib/api";
import { getter, key } from "../../lib/query";
import { can } from "../../lib/permissions";
import { useHasMounted } from "../../lib/useHasMounted";
import { meterPct } from "./planAccount";
import { shiftMonth, topAlert, totals, type MessageUsage } from "./messageUsage";
import styles from "./planAccount.module.css";

const money = (minor: number, currency: string) => formatMoney(minor, currency, { minor: true });

export default function MessageUsagePanel() {
  const mounted = useHasMounted();
  // never mounted before the permission answer: the request is not even sent for a role without setup:view
  return mounted && can("setup:view") ? <Usage /> : null;
}

function Usage() {
  const t = useTranslations("billing.messageUsage");
  // null = the clinic's current month (the server knows the clinic's time zone)
  const [month, setMonth] = useState<string | null>(null);
  const currentQ = useQuery({ queryKey: key("message-usage", { month: "current" }), queryFn: getter<MessageUsage>("/owner/messaging/usage") });
  const pastQ = useQuery({
    queryKey: key("message-usage", { month: month ?? "" }),
    queryFn: getter<MessageUsage>(`/owner/messaging/usage?month=${month}`),
    enabled: month !== null,
  });
  const q = month === null ? currentQ : pastQ;
  const shown = month ?? currentQ.data?.month ?? null;
  const label = (kind: "channel" | "category", code: string) => (t.has(`${kind}.${code}`) ? t(`${kind}.${code}`) : code);

  return (
    <section className={styles.panel} aria-labelledby="message-usage-h">
      <h2 id="message-usage-h" className={styles.h2}>{t("title")}</h2>
      {shown && (
        <div className={styles.row}>
          <strong>{t("month", { month: shown })}</strong>
          <span className={styles.actions}>
            <button type="button" className={styles.btnGhost} onClick={() => setMonth(shiftMonth(shown, -1))}>{t("previous")}</button>
            {month !== null && (
              <button type="button" className={styles.btnGhost}
                onClick={() => { const next = shiftMonth(month, 1); setMonth(next === currentQ.data?.month ? null : next); }}>{t("next")}</button>
            )}
          </span>
        </div>
      )}
      {q.isPending ? <Skeleton rows={3} label={t("loading")} />
        : q.isError ? <ErrorState message={reasonText(q.error, t("loadFailed"))} onRetry={() => void q.refetch()} />
        : <Month data={q.data} label={label} />}
    </section>
  );
}

function Month({ data, label }: { data: MessageUsage; label: (kind: "channel" | "category", code: string) => string }) {
  const t = useTranslations("billing.messageUsage");
  const alert = topAlert(data.alerts);
  const a = data.allowance ?? null;
  const pct = a ? meterPct(a.used, a.limit) : null;
  return (
    <>
      {alert && (
        <div className={styles.warn} role="status" data-testid="usage-alert">
          {alert.thresholdPct >= 100 ? t("alert100") : t("alert80", { pct: alert.thresholdPct })}
        </div>
      )}
      {a && pct !== null && (
        <div className={styles.card} data-testid="usage-allowance">
          <div className={styles.meter}>
            <div className={styles.row}><span>{t("allowance")}</span><span>{t("usedOf", { used: a.used, limit: a.limit })}</span></div>
            <div className={styles.bar} role="meter" aria-label={t("allowance")} aria-valuemin={0} aria-valuemax={a.limit} aria-valuenow={a.used}>
              <span className={pct >= 80 ? styles.fillFull : styles.fill} style={{ inlineSize: `${pct}%` }} />
            </div>
          </div>
          <span className={styles.muted}>{t("alertsAt")}</span>
        </div>
      )}
      {data.channels.length === 0 ? <EmptyState icon="info" title={t("empty")} hint={t("emptyHint")} /> : (
        <div className={styles.tableWrap}>
          <table className={styles.table} data-testid="usage-table">
            <caption className={styles.muted}>{t("caption")}</caption>
            <thead>
              <tr><th scope="col">{t("colChannel")}</th><th scope="col">{t("colCategory")}</th><th scope="col" className={styles.num}>{t("colCount")}</th><th scope="col" className={styles.num}>{t("colCost")}</th></tr>
            </thead>
            <tbody>
              {data.channels.map((i) => (
                <tr key={`${i.channel}:${i.category}`}>
                  <td>{label("channel", i.channel)}</td><td>{label("category", i.category)}</td>
                  <td className={styles.num}>{i.count}</td><td className={styles.num}>{money(i.costMinor, i.currency)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              {totals(data.channels).map((s) => (
                <tr key={s.currency}>
                  <th scope="row" colSpan={2}>{t("total", { currency: s.currency })}</th>
                  <td className={styles.num}>{s.count}</td><td className={styles.num}>{money(s.costMinor, s.currency)}</td>
                </tr>
              ))}
            </tfoot>
          </table>
        </div>
      )}
    </>
  );
}
