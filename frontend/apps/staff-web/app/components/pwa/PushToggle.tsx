"use client";
// FEF-048: "Notify me when a patient is ready" for this device (Account page). Doctors only: the push goes to the doctor of the
// queue entry, so for anyone else the control is absent. Subscribes through the service worker with the deployment's VAPID key,
// then registers the subscription at Core; turning it off (or signing out, lib/session.ts) removes both.
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { api } from "../../lib/api";
import { getPermissions } from "../../lib/session";
import { PUSH_SUB_KEY, pushSupport, vapidKeyBytes, type PushSupport } from "../../lib/push";
import styles from "../../(clinic)/account/account.module.css";

const VAPID = process.env.NEXT_PUBLIC_VAPID_PUBLIC_KEY;
type State = "loading" | PushSupport | "blocked";

export default function PushToggle() {
  const t = useTranslations("shell.pwa");
  const [doctor, setDoctor] = useState(false);
  const [state, setState] = useState<State>("loading");
  const [on, setOn] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);

  useEffect(() => {
    void (async () => {
      setDoctor(getPermissions().includes("clinical:edit"));
      const support = pushSupport({ serviceWorker: "serviceWorker" in navigator, pushManager: "PushManager" in window, notification: "Notification" in window, vapidKey: VAPID });
      if (support !== "supported") { setState(support); return; }
      if (Notification.permission === "denied") { setState("blocked"); return; }
      const reg = await navigator.serviceWorker.getRegistration().catch(() => undefined);
      const sub = await reg?.pushManager.getSubscription().catch(() => null);
      let stored: string | null = null;
      try { stored = localStorage.getItem(PUSH_SUB_KEY); } catch { /* blocked storage */ }
      setOn(!!sub && !!stored);
      setState("supported");
    })();
  }, []);

  async function enable() {
    if (await Notification.requestPermission() !== "granted") { setState("blocked"); return; }
    const reg = await navigator.serviceWorker.ready;
    const sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: vapidKeyBytes(VAPID ?? "") });
    const json = sub.toJSON();
    const { id } = await api.post<{ id: string }>("/me/push-subscriptions", { endpoint: json.endpoint, keys: json.keys, deviceLabel: null });
    localStorage.setItem(PUSH_SUB_KEY, id);
  }

  async function disable() {
    const id = localStorage.getItem(PUSH_SUB_KEY);
    if (id) await api.delete(`/me/push-subscriptions/${encodeURIComponent(id)}`);
    localStorage.removeItem(PUSH_SUB_KEY);
    await (await (await navigator.serviceWorker.getRegistration())?.pushManager.getSubscription())?.unsubscribe();
  }

  async function toggle() {
    setBusy(true);
    setError(false);
    try {
      if (on) await disable(); else await enable();
      setOn(!on);
    } catch {
      setError(true);
    } finally {
      setBusy(false);
    }
  }

  if (!doctor) return null;
  return (
    <>
      <h2 className={styles.sectionTitle}>{t("notifyTitle")}</h2>
      <div className={styles.card} data-testid="push-toggle" data-state={state}>
        {state === "loading" && <div className={styles.state}>{t("notifyLoading")}</div>}
        {state === "unsupported" && <p className={styles.muted}>{t("notifyUnsupported")}</p>}
        {state === "not-configured" && <p className={styles.muted}>{t("notifyNotConfigured")}</p>}
        {state === "blocked" && <p className={styles.muted} role="alert">{t("notifyBlocked")}</p>}
        {state === "supported" && (
          <>
            <label className={styles.toggleRow}>
              <input type="checkbox" role="switch" checked={on} disabled={busy} onChange={() => void toggle()} />
              <span>{t("notifyToggle")}</span>
            </label>
            <p className={styles.muted}>{t("notifyHint")}</p>
            {error && <div className={styles.formError} role="alert">{t("notifyFailed")}</div>}
          </>
        )}
      </div>
    </>
  );
}
