import type { PackageCard } from "../lib/patientApi";
import { formatMoney, formatWhen, type T } from "./util";
import styles from "./linkflow.module.css";

/** COM-044: the patient's package card (sessions used/left, expiry, paid/due, savings). Read-only; booking happens in the WhatsApp chat. */
export default function PackageCardView({ card, t, locale }: { card: PackageCard; t: T; locale: string }) {
  const left = Math.max(0, card.sessionsTotal - card.sessionsUsed);
  const money = (n: number) => formatMoney(n, locale, card.currency);
  const bookable = (card.status === "active" || card.status === "grace") && left > 0;
  return (
    <section className={styles.card}>
      <h1 className={styles.h1}>{t("pkgTitle")}</h1>
      <p className={styles.muted}>{card.packageName}</p>
      <dl className={styles.dl}>
        <div><dt>{t("pkgStatus")}</dt><dd>{t(`pkgStatus_${card.status}`)}</dd></div>
        <div><dt>{t("pkgSessions")}</dt><dd>{t("pkgSessionsValue", { used: String(card.sessionsUsed), total: String(card.sessionsTotal), left: String(left) })}</dd></div>
        {card.items.length > 1 && card.items.map((i) => <div key={i.name}><dt>{i.name}</dt><dd>{i.used}/{i.total}</dd></div>)}
        {card.validUntil && <div><dt>{t("pkgValidUntil")}</dt><dd>{formatWhen(card.validUntil, locale)}</dd></div>}
        {card.status === "grace" && card.graceUntil && <div><dt>{t("pkgGraceUntil")}</dt><dd>{formatWhen(card.graceUntil, locale)}</dd></div>}
        <div><dt>{t("pkgPaid")}</dt><dd>{money(card.paid)}</dd></div>
        {card.due > 0 && <div><dt>{t("pkgDue")}</dt><dd>{money(card.due)}</dd></div>}
      </dl>
      {card.saved > 0 && <p className={styles.ok}>{t("pkgSaved", { amount: money(card.saved) })}</p>}
      {bookable && <p className={styles.muted}>{t("pkgBookHint", { next: String(card.sessionsUsed + 1), total: String(card.sessionsTotal) })}</p>}
    </section>
  );
}

