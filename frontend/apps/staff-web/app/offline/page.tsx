import { getTranslations } from "next-intl/server";
import Icon from "../components/ui/Icon";
import styles from "./offline.module.css";

// FEF-048: what the service worker shows when a page cannot be loaded (public/sw.js precaches it; no data, no patient, no API
// call). It works without JavaScript: "Try again" is a plain GET of the address the user asked for.
export default async function OfflinePage() {
  const t = await getTranslations("shell.pwa");
  return (
    <main className={styles.page} data-testid="offline-page">
      <Icon name="alert" size={28} />
      <h1 className={styles.title}>{t("offlineTitle")}</h1>
      <p>{t("offlineBody")}</p>
      <p>{t("offlineDrafts")}</p>
      <p className={styles.warn} role="note">{t("offlinePayments")}</p>
      <form method="get"><button type="submit" className={styles.retry}>{t("offlineRetry")}</button></form>
    </main>
  );
}
