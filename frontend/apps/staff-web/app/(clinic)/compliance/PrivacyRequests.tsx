"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import styles from "./compliance.module.css";
import StatusPill from "../../components/ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import { api, ApiError } from "../../lib/api";
import { formatClinicDate } from "../../lib/clinicTime";
import { useClinicTimeZone } from "../../lib/useClinicTimeZone";
import { countdown, FILTERS, loadProblem, queueQuery, type DsrItem, type Filter } from "./privacyRules";

// PLT-047 (prototype 'owner.privacy'). Matches GET /v1/privacy/requests (compliance:view; the page is already gated on it).
// Patients' requests for a copy of their data, correction or erasure, soonest due date first, with a countdown chip.
export default function PrivacyRequests() {
  const t = useTranslations("common.privacyRequests");
  const tz = useClinicTimeZone();
  const [filter, setFilter] = useState<Filter>("open");
  const [type, setType] = useState<DsrItem["type"] | "">("");
  const [items, setItems] = useState<DsrItem[] | null>(null);
  const [next, setNext] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState(0); // "days left" is judged as of the last load (render must stay pure)

  const load = useCallback(async (cursor?: string) => {
    setError(null);
    try {
      const page = await api.get<{ items: DsrItem[]; nextCursor: string | null }>(`/privacy/requests${queueQuery(filter, type, cursor)}`);
      setItems((prev) => (cursor ? [...(prev ?? []), ...page.items] : page.items));
      setNext(page.nextCursor);
      setNow(Date.now());
    } catch (e) {
      setError(t(loadProblem(e instanceof ApiError ? e.status : 0)));
    }
  }, [filter, type, t]);
  useEffect(() => { void Promise.resolve().then(() => load()); }, [load]);

  return (
    <section className={styles.card} aria-labelledby="pr-h" id="privacy">
      <h2 id="pr-h" className={styles.h2}>{t("title")}</h2>
      <p className={styles.muted}>{t("intro")}</p>
      <div className={styles.actions} role="group" aria-label={t("title")}>
        {FILTERS.map((f) => (
          <button key={f} type="button" className={filter === f ? styles.primary : styles.secondary} aria-pressed={filter === f}
            onClick={() => { setItems(null); setFilter(f); }}>{t(`filter.${f}`)}</button>
        ))}
        <select aria-label={t("typeFilter")} className={styles.input} value={type} onChange={(e) => { setItems(null); setType(e.target.value as DsrItem["type"] | ""); }}>
          <option value="">{t("type.all")}</option>
          {(["access", "correction", "erasure"] as const).map((k) => <option key={k} value={k}>{t(`type.${k}`)}</option>)}
        </select>
      </div>
      {items === null && !error ? <Skeleton rows={3} label={t("loading")} />
        : error ? <ErrorState message={error} onRetry={() => load()} />
        : items!.length === 0 ? <EmptyState icon="shield" title={t("empty")} hint={t("emptyHint")} />
        : (
          <>
            {items!.map((r) => {
              const c = countdown(r, now);
              return (
                <article key={r.requestId} className={styles.req} data-testid="privacy-request">
                  <div>
                    <strong>{r.patientName}</strong>{r.mrn ? ` (${r.mrn})` : ""}{" "}
                    <StatusPill tone={c.tone} label={c.key === "closed" ? t(`status.${r.status}`) : t(`chip.${c.key}`, { days: c.days })} />
                  </div>
                  <div className={styles.muted}>{t(`type.${r.type}`)} · {t(`channel.${r.channel}`)}{r.requesterType === "caregiver" ? ` · ${t("byGuardian")}` : ""}</div>
                  <div className={styles.muted}>{t("received", { date: formatClinicDate(r.receivedAt, undefined, tz) })} · {t("due", { date: formatClinicDate(r.dueAt, undefined, tz) })}</div>
                  {c.key !== "closed" && <div className={styles.muted}>{t(`status.${r.status}`)}{r.reminded ? ` · ${t("reminded")}` : ""}</div>}
                </article>
              );
            })}
            {next && <button type="button" className={styles.secondary} onClick={() => load(next)}>{t("loadMore")}</button>}
          </>
        )}
    </section>
  );
}
