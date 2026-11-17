"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { ErrorState, Skeleton } from "../../components/ui/StateViews";
import { ApiError, api } from "../../lib/api";
import { getter, key } from "../../lib/query";
import styles from "./patients.module.css";
import own from "./notificationPrefs.module.css";
import {
  CAREGIVER_CATEGORIES, CATEGORY_KEYS, CHANNELS, DEFAULT_QUIET, LANGUAGES, LANGUAGE_NAMES,
  caregiverHas, prefsErrorKey, toRequest, toggleCaregiver, type Prefs,
} from "./notificationPrefs";

/** COM-025: patient file > Message preferences. Each change saves at once (PUT replaces the whole set; an unchanged set writes
 * nothing), so there is no draft to lose on a tab switch. Without patients:edit the values are shown as text, with no controls.
 * Without patients:view the GET is 403 and the panel renders nothing. */
export default function NotificationPrefs({ patientId, canEdit }: { patientId: string; canEdit: boolean }) {
  const t = useTranslations("common.notifPrefs");
  const qc = useQueryClient();
  const qk = key("patient-notification-prefs", { id: patientId });
  const q = useQuery({ queryKey: qk, queryFn: getter<Prefs>(`/patients/${patientId}/notification-preferences`) });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (q.error instanceof ApiError && q.error.status === 403) return null;
  if (!q.data) {
    return q.isError
      ? <ErrorState message={t("loadError")} onRetry={() => void q.refetch()} />
      : <Skeleton rows={3} label={t("title")} />;
  }
  const p = q.data;

  async function save(next: Prefs) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      qc.setQueryData(qk, await api.put<Prefs>(`/patients/${patientId}/notification-preferences`, toRequest(next)));
    } catch (e) {
      setError(t(`errors.${prefsErrorKey(e)}`));
      void q.refetch(); // show what is really saved
    } finally {
      setBusy(false);
    }
  }

  const languageLabel = p.language ? LANGUAGE_NAMES[p.language as keyof typeof LANGUAGE_NAMES] ?? p.language : t("languageDefault");
  const name = (ref: string) => {
    const c = p.eligibleCaregivers.find((e) => e.personRef === ref);
    return c ? `${c.firstName ?? t("caregiverUnnamed")} (${c.relation})` : t("caregiverUnnamed");
  };

  if (!canEdit) {
    return (
      <section className={styles.section} aria-label={t("title")}>
        <div className={styles.sectionLabel}>{t("title")}</div>
        <dl className={own.facts}>
          <dt>{t("language")}</dt><dd>{languageLabel}</dd>
          <dt>{t("channel")}</dt><dd>{t(`channels.${p.channel}`)}</dd>
          {CATEGORY_KEYS.map((c) => (<FactRow key={c} label={t(`categories.${c}`)} value={p.categories[c] ? t("on") : t("off")} />))}
          <dt>{t("quietHours")}</dt><dd>{p.quietHours ? `${p.quietHours.start}–${p.quietHours.end}` : t("off")}</dd>
          <dt>{t("caregivers")}</dt>
          <dd>{p.caregivers.length === 0 ? t("caregiversNone") : p.caregivers.map((c) => `${name(c.personRef)}: ${c.categories.map((k) => t(`caregiverCategories.${k}`)).join(", ")}`).join("; ")}</dd>
        </dl>
      </section>
    );
  }

  return (
    <section className={styles.section} aria-label={t("title")} aria-busy={busy}>
      <div className={styles.sectionLabel}>{t("title")}</div>
      <div className={own.grid}>
        <label className={own.field}>
          <span className={styles.fieldLabel}>{t("language")}</span>
          <select className={styles.input} value={p.language ?? ""} disabled={busy}
            onChange={(e) => void save({ ...p, language: e.target.value || null })}>
            <option value="">{t("languageDefault")}</option>
            {LANGUAGES.map((l) => <option key={l} value={l}>{LANGUAGE_NAMES[l]}</option>)}
          </select>
        </label>
        <label className={own.field}>
          <span className={styles.fieldLabel}>{t("channel")}</span>
          <select className={styles.input} value={p.channel} disabled={busy} onChange={(e) => void save({ ...p, channel: e.target.value })}>
            {CHANNELS.map((c) => <option key={c} value={c}>{t(`channels.${c}`)}</option>)}
          </select>
        </label>
      </div>

      <fieldset className={own.group}>
        <legend className={styles.fieldLabel}>{t("receives")}</legend>
        {CATEGORY_KEYS.map((c) => (
          <label key={c} className={own.check}>
            <input type="checkbox" checked={p.categories[c]} disabled={busy}
              onChange={(e) => void save({ ...p, categories: { ...p.categories, [c]: e.target.checked } })} />
            {t(`categories.${c}`)}
          </label>
        ))}
      </fieldset>

      <fieldset className={own.group}>
        <legend className={styles.fieldLabel}>{t("quietHours")}</legend>
        <label className={own.check}>
          <input type="checkbox" checked={!!p.quietHours} disabled={busy}
            onChange={(e) => void save({ ...p, quietHours: e.target.checked ? DEFAULT_QUIET : null })} />
          {t("quietHoursOn")}
        </label>
        {p.quietHours && (
          <div className={own.grid}>
            <label className={own.field}>
              <span className={styles.fieldLabel}>{t("quietFrom")}</span>
              <input type="time" className={styles.input} defaultValue={p.quietHours.start} disabled={busy}
                onBlur={(e) => { if (e.target.value && e.target.value !== p.quietHours?.start) void save({ ...p, quietHours: { start: e.target.value, end: p.quietHours!.end } }); }} />
            </label>
            <label className={own.field}>
              <span className={styles.fieldLabel}>{t("quietTo")}</span>
              <input type="time" className={styles.input} defaultValue={p.quietHours.end} disabled={busy}
                onBlur={(e) => { if (e.target.value && e.target.value !== p.quietHours?.end) void save({ ...p, quietHours: { start: p.quietHours!.start, end: e.target.value } }); }} />
            </label>
          </div>
        )}
      </fieldset>

      <fieldset className={own.group}>
        <legend className={styles.fieldLabel}>{t("caregivers")}</legend>
        {p.eligibleCaregivers.length === 0 ? (
          <div className={styles.muted}>{t("caregiversEmpty")}</div>
        ) : p.eligibleCaregivers.map((c) => (
          <div key={c.personRef} className={own.caregiver}>
            <span>{name(c.personRef)}</span>
            {CAREGIVER_CATEGORIES.map((k) => (
              <label key={k} className={own.check}>
                <input type="checkbox" checked={caregiverHas(p.caregivers, c.personRef, k)} disabled={busy}
                  onChange={(e) => void save({ ...p, caregivers: toggleCaregiver(p.caregivers, c.personRef, k, e.target.checked) })} />
                {t(`caregiverCategories.${k}`)}
              </label>
            ))}
          </div>
        ))}
      </fieldset>

      <div role="status" className={styles.muted}>{busy ? t("saving") : ""}</div>
      {error && <div className={styles.errorInline} role="alert">{error}</div>}
    </section>
  );
}

function FactRow({ label, value }: { label: string; value: string }) {
  return (<><dt>{label}</dt><dd>{value}</dd></>);
}
