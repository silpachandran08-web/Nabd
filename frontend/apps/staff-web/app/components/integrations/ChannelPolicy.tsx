"use client";
// COM-016 owner.integrations: the WhatsApp channel exclusivity policy in plain words. Static copy (no fetch, so no loading or error
// state); the Patient Access Edge enforces it in code (tenant-bound content, output guard).
import { useTranslations } from "next-intl";
import Icon from "../ui/Icon";
import styles from "./channelPolicy.module.css";

const POLICY_POINTS = ["onlyYours", "never", "guard", "discovery"] as const;

export default function ChannelPolicy() {
  const t = useTranslations("common.channelPolicy");
  return (
    <main className={styles.page}>
      <h1 className={styles.title}>{t("pageTitle")}</h1>
      <section className={styles.card} aria-labelledby="channel-policy-h" data-testid="channel-policy">
        <h2 id="channel-policy-h" className={styles.h2}>
          <Icon name="shield" size={18} />
          {t("title")}
        </h2>
        <p className={styles.lead}>{t("lead")}</p>
        <ul className={styles.list}>
          {POLICY_POINTS.map((k) => (
            <li key={k}>{t(`points.${k}`)}</li>
          ))}
        </ul>
      </section>
    </main>
  );
}
