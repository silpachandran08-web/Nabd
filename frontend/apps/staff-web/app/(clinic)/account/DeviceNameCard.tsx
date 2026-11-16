"use client";

import { useState, type FormEvent } from "react";
import { useTranslations } from "next-intl";
import styles from "./account.module.css";
import { DEVICE_LABEL_MAX, getDeviceLabel, setDeviceLabel } from "../../lib/deviceLabel";
import { useHasMounted } from "../../lib/useHasMounted";

// PLT-013: names this browser ("Front desk PC 2"); sent as X-Device-Label at sign-in and break-glass activation.
export default function DeviceNameCard() {
  const t = useTranslations("common.deviceLabel");
  const mounted = useHasMounted();
  const [edited, setName] = useState<string | null>(null); // null = show what is stored (read after mount: localStorage)
  const name = edited ?? (mounted ? getDeviceLabel() : "");
  const [saved, setSaved] = useState(false);

  function save(e: FormEvent) {
    e.preventDefault();
    setName(setDeviceLabel(name) || getDeviceLabel());
    setSaved(true);
  }

  return (
    <>
      <h2 className={styles.sectionTitle}>{t("title")}</h2>
      <form className={styles.card} onSubmit={save}>
        <p className={styles.muted}>{t("hint")}</p>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="deviceName">{t("label")}</label>
          <input id="deviceName" className={styles.input} maxLength={DEVICE_LABEL_MAX} value={name}
            onChange={(e) => { setName(e.target.value); setSaved(false); }} />
        </div>
        <button className={styles.btnPrimary} type="submit">{t("save")}</button>
        {saved && <p className={styles.muted} role="status">{t("saved")}</p>}
      </form>
    </>
  );
}
