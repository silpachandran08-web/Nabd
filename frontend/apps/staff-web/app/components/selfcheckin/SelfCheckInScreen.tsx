"use client";

import { useCallback, useEffect, useState } from "react";
import Can from "../ui/Can";
import StatusPill from "../ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { api, ApiError, errorText } from "../../lib/api";
import { formatClinicTime } from "../../lib/clinicTime";
import { useClinicTimeZone } from "../../lib/useClinicTimeZone";
import { useLiveRefresh } from "../../lib/live";
import { usePolling } from "../../lib/usePolling";
import { channelLabel, check, formOf, needsDesk, outcomeLabel, type Attempt, type Config, type Form } from "./selfCheckinLogic";
import styles from "./selfcheckin.module.css";

/** reception.checkin (ACC-033): the door poster and its settings (clinic admins), recent self check-ins, and the failures the desk can confirm in one tap. */
export default function SelfCheckInScreen({ children }: { children?: React.ReactNode }) {
  return (
    <main className={styles.page}>
      <header>
        <h1 className={styles.title}>Self check-in</h1>
        <p className={styles.subtitle}>Patients scan the code at the door or tap “I’m here” in WhatsApp and get their token. Anyone who can’t is sent to you below.</p>
      </header>
      {children}
      <Can permission="setup:edit"><Poster /></Can>
      <Recent />
    </main>
  );
}

function Poster() {
  const [cfg, setCfg] = useState<Config | null>(null);
  const [form, setForm] = useState<Form>(formOf(null));
  const [loadFailed, setLoadFailed] = useState(false);
  const [msg, setMsg] = useState<{ tone: "ok" | "error"; text: string } | null>(null);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    try {
      const c = await api.get<Config>("/facility/checkin-config");
      setCfg(c);
      setForm(formOf(c));
      setLoadFailed(false);
    } catch { setLoadFailed(true); }
  }, []);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  async function save() {
    const r = check(form);
    if (!r.ok) { setMsg({ tone: "error", text: r.error }); return; }
    setSaving(true);
    try {
      const c = await api.put<Config>("/facility/checkin-config", r.body);
      setCfg(c);
      setForm(formOf(c));
      setMsg({ tone: "ok", text: "Saved." });
    } catch (e) { setMsg({ tone: "error", text: errorText(e, "Couldn't save the settings. Nothing changed.") }); }
    finally { setSaving(false); }
  }

  async function download(format: "png" | "pdf") {
    try {
      const blob = await api.get<Blob>(`/facility/checkin-qr?format=${format}`, { as: "blob" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url; a.download = `checkin-poster.${format}`;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) {
      setMsg({ tone: "error", text: e instanceof ApiError && e.code === "CHECKIN_NOT_CONFIGURED" ? "Save the clinic’s WhatsApp number first, then download the poster." : errorText(e, "Couldn't make the poster.") });
    }
  }

  function useThisDevice() {
    if (!navigator.geolocation) { setMsg({ tone: "error", text: "This device can’t share its location. Type the coordinates instead." }); return; }
    navigator.geolocation.getCurrentPosition(
      (p) => setForm((f) => ({ ...f, lat: p.coords.latitude.toFixed(6), lng: p.coords.longitude.toFixed(6) })),
      () => setMsg({ tone: "error", text: "Couldn’t read this device’s location. Type the coordinates instead." }),
    );
  }

  if (loadFailed && !cfg) return <section className={styles.card}><ErrorState message="Couldn't load the check-in settings." onRetry={() => void load()} /></section>;
  if (!cfg) return <section className={styles.card}><Skeleton rows={3} label="Loading the check-in settings" /></section>;
  const set = (k: keyof Form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm((f) => ({ ...f, [k]: e.target.type === "checkbox" ? e.target.checked : e.target.value }));
  return (
    <section className={styles.card} aria-label="Door poster and settings">
      <h2 className={styles.h2}>Door poster</h2>
      {cfg.checkinCode ? <p className={styles.meta}>Your clinic’s code: <span className={styles.code}>{cfg.checkinCode}</span>. The poster’s QR opens WhatsApp with “CHECKIN {cfg.checkinCode}” ready to send.</p>
        : <p className={styles.meta}>Save your WhatsApp number below to create your clinic’s code and poster.</p>}
      <div className={styles.row}>
        <button type="button" className={styles.secondary} onClick={() => void download("png")}>Download poster (PNG)</button>
        <button type="button" className={styles.secondary} onClick={() => void download("pdf")}>Download poster (PDF)</button>
      </div>
      <h2 className={styles.h2} style={{ marginBlockStart: "var(--nb-space-16)" }}>Settings</h2>
      <div className={styles.grid}>
        <label className={styles.field}>Clinic WhatsApp number<input inputMode="tel" value={form.wa} onChange={set("wa")} placeholder="919876543210" /></label>
        <label className={styles.field}>Latitude<input inputMode="decimal" value={form.lat} onChange={set("lat")} /></label>
        <label className={styles.field}>Longitude<input inputMode="decimal" value={form.lng} onChange={set("lng")} /></label>
        <label className={styles.field}>Distance allowed (metres)<input inputMode="numeric" value={form.radius} onChange={set("radius")} /></label>
      </div>
      <label className={styles.check}><input type="checkbox" checked={form.geofence} onChange={set("geofence")} /> Patients must share their location and be at the clinic</label>
      <label className={styles.check}><input type="checkbox" checked={form.walkin} onChange={set("walkin")} /> Let walk-ins ask for a token with the QR</label>
      {msg && <div className={msg.tone === "error" ? styles.error : styles.ok} role={msg.tone === "error" ? "alert" : "status"}>{msg.text}</div>}
      <div className={styles.row}>
        <button type="button" className={styles.secondary} onClick={useThisDevice}>Use this device’s location</button>
        <button type="button" className={styles.primary} disabled={saving} onClick={() => void save()}>Save settings</button>
      </div>
    </section>
  );
}

function Recent() {
  const tz = useClinicTimeZone();
  const [items, setItems] = useState<Attempt[] | null>(null);
  const [failed, setFailed] = useState(false);
  const load = useCallback(async () => {
    try { setItems(await api.get<Attempt[]>("/facility/checkin-attempts?limit=20")); setFailed(false); } catch { setFailed(true); }
  }, []);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);
  useLiveRefresh(["queue", "desk-exceptions"], load);
  usePolling(load, 20_000);
  return (
    <section className={styles.card} aria-label="Recent self check-ins">
      <h2 className={styles.h2}>Recent self check-ins</h2>
      {items === null && !failed && <Skeleton rows={3} label="Loading recent check-ins" />}
      {items === null && failed && <ErrorState message="Couldn't load recent check-ins." onRetry={() => void load()} />}
      {items !== null && items.length === 0 && <EmptyState icon="qr" title="No self check-ins yet." hint="They show up here as patients use the door QR or WhatsApp." />}
      <ul className={styles.list}>
        {(items ?? []).map((a) => (
          <li key={a.id} className={styles.item}>
            <span>{formatClinicTime(a.createdAt, tz)} · {channelLabel(a.channel)}{a.distanceM != null ? ` · ${a.distanceM} m` : ""}</span>
            <StatusPill tone={a.outcome === "checked_in" ? "ok" : needsDesk(a.outcome) ? "warn" : "info"} label={outcomeLabel(a.outcome)} />
          </li>
        ))}
      </ul>
    </section>
  );
}
