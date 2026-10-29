"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { patientApi, PatientApiError, type LinkInfo, type Resolved } from "../lib/patientApi";
import { makeT, type T } from "./util";
import OtpGate from "./OtpGate";
import DocumentView from "./DocumentView";
import ConsentView from "./ConsentView";
import BookingView from "./BookingView";
import styles from "./linkflow.module.css";

const LOCALE_LABELS: Record<string, string> = { en: "English", hi: "हिन्दी", kn: "ಕನ್ನಡ", te: "తెలుగు" };

type Phase = { name: "loading" } | { name: "expired" } | { name: "error"; resume?: { info: LinkInfo; token: string } } | { name: "otp"; info: LinkInfo } | { name: "content"; info: LinkInfo; token: string; resolved: Resolved };

/** One page for every secure link: link info -> OTP -> the content the link points at (document, consent request or booking). */
export default function LinkFlow({ linkId, locale, messages }: { linkId: string | null; locale: string; messages: Record<string, string> }) {
  const t = useMemo(() => makeT(messages), [messages]);
  const api = useMemo(() => patientApi(), []);
  const [phase, setPhase] = useState<Phase>(linkId ? { name: "loading" } : { name: "expired" });
  const [clinic, setClinic] = useState<string | null>(null);

  const start = useCallback(async () => {
    if (!linkId) return;
    setPhase({ name: "loading" });
    try {
      const info = await api.linkInfo(linkId);
      setClinic(info.clinicName);
      setPhase({ name: "otp", info });
    } catch (e) {
      setPhase(e instanceof PatientApiError && e.failure === "expired" ? { name: "expired" } : { name: "error" });
    }
  }, [api, linkId]);
  useEffect(() => { void Promise.resolve().then(start); }, [start]);

  const onVerified = useCallback(async (info: LinkInfo, token: string) => {
    if (!linkId) return;
    setPhase({ name: "loading" });
    try {
      setPhase({ name: "content", info, token, resolved: await api.content(linkId, token) });
    } catch (e) {
      setPhase(e instanceof PatientApiError && e.failure === "expired" ? { name: "expired" } : { name: "error", resume: { info, token } });
    }
  }, [api, linkId]);

  return (
    <main className={styles.page} aria-busy={phase.name === "loading"}>
      <header className={styles.bar}>
        <p className={styles.clinic}>{clinic ?? t("title")}</p>
        <LanguagePicker locale={locale} t={t} />
      </header>
      {phase.name === "loading" && <div className={styles.skel} role="status" aria-label={t("loading")} />}
      {phase.name === "expired" && (
        <section className={styles.card}>
          <h1 className={styles.h1}>{t("expiredTitle")}</h1>
          <p className={styles.muted}>{t("expiredHint")}</p>
        </section>
      )}
      {phase.name === "error" && (
        <section className={styles.card} role="alert">
          <h1 className={styles.h1}>{t("errorTitle")}</h1>
          <p className={styles.muted}>{t("errorHint")}</p>
          <button type="button" className={styles.btn} onClick={() => { const r = phase.resume; if (r) void onVerified(r.info, r.token); else void start(); }}>{t("retry")}</button>
        </section>
      )}
      {phase.name === "otp" && linkId && <OtpGate linkId={linkId} clinic={phase.info.clinicName} api={api} t={t} onVerified={(token) => void onVerified(phase.info, token)} onExpired={() => setPhase({ name: "expired" })} />}
      {phase.name === "content" && linkId && <Content linkId={linkId} locale={locale} t={t} messages={messages} phase={phase} api={api} onExpired={() => setPhase({ name: "expired" })} onReload={() => void onVerified(phase.info, phase.token)} />}
    </main>
  );
}

function Content({ linkId, locale, t, messages, phase, api, onExpired, onReload }: {
  linkId: string; locale: string; t: T; messages: Record<string, string>; phase: Extract<Phase, { name: "content" }>; api: ReturnType<typeof patientApi>; onExpired: () => void; onReload: () => void;
}) {
  const { resolved, token } = phase;
  if (resolved.kind === "document") return <DocumentView linkId={linkId} token={token} resolved={resolved} api={api} t={t} onExpired={onExpired} />;
  if (resolved.kind === "consent") return <ConsentView summary={resolved.consent} token={token} api={api} t={t} messages={messages} locale={locale} onExpired={onExpired} onReload={onReload} />;
  return <BookingView booking={resolved.booking} t={t} locale={locale} />;
}

function LanguagePicker({ locale, t }: { locale: string; t: T }) {
  return (
    <div>
      <label className={styles.pickerLabel} htmlFor="lang">{t("langLabel")}</label>
      <select id="lang" className={styles.picker} value={locale} onChange={(e) => {
        const url = new URL(window.location.href);
        url.searchParams.set("lang", e.target.value);
        window.location.assign(url.toString()); // the proxy renders the new language server-side
      }}>
        {Object.entries(LOCALE_LABELS).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
      </select>
    </div>
  );
}
