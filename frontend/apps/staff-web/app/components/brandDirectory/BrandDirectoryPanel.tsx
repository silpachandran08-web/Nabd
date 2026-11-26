"use client";

// PLT-024: the 'Registered at <branch>' section under the patient search. Absent unless the brand shares demographics
// (GET /v1/patients/brand-directory/status). A check is an explicit tap: every lookup is logged at both branches.
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { api, ApiError, intentKeys } from "../../lib/api";
import { Skeleton } from "../ui/StateViews";
import { clinicToday } from "../../lib/clinicTime";
import styles from "./brandDirectory.module.css";
import { directoryPhone, dobOk, linkBody, linkProblem, lookupPath, sexKey, type Lookup, type Match } from "./brandDirectory";

export default function BrandDirectoryPanel({ query, onLinked }: { query: string; onLinked: (patientId: string) => void }) {
  const t = useTranslations("shell.brandDirectory");
  const [enabled, setEnabled] = useState(false);
  const [result, setResult] = useState<{ phone: string; lookup: Lookup } | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState<string | null>(null);
  const phone = directoryPhone(query);

  useEffect(() => {
    api.get<{ enabled: boolean }>("/patients/brand-directory/status").then((r) => setEnabled(r.enabled)).catch(() => setEnabled(false));
  }, []);
  // A new number is a new question: the previous answer is not shown next to it.
  useEffect(() => { setResult(null); setFailed(null); }, [phone]); // eslint-disable-line react-hooks/set-state-in-effect

  if (!enabled || !phone) return null;

  async function check() {
    if (busy || !phone) return;
    setBusy(true);
    setFailed(null);
    try {
      setResult({ phone, lookup: await api.get<Lookup>(lookupPath(phone)) });
    } catch (e) {
      setResult(null);
      setFailed(e instanceof ApiError && e.status === 429 ? t("tooMany") : t("failed"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className={styles.panel} aria-labelledby="bd-h">
      <div className={styles.row}>
        <h2 id="bd-h" className={styles.h2}>{t("title")}</h2>
        <button type="button" className={styles.btnGhost} disabled={busy} onClick={() => void check()}>{t("search")}</button>
      </div>
      <div className={styles.muted}>{t("searchHint")}</div>
      {busy && <Skeleton rows={2} label={t("loading")} />}
      {failed && <div className={styles.error} role="alert">{failed}</div>}
      {result && !busy && (result.lookup.matches.length === 0 ? (
        <div role="status"><div>{t("none")}</div><div className={styles.muted}>{t("noneHint")}</div></div>
      ) : (
        <ul className={styles.list} aria-label={t("title")}>
          {result.lookup.matches.map((m) => <MatchRow key={`${m.sourceTenantId}:${m.sourcePatientRef}`} match={m} phone={result.phone} onLinked={onLinked} />)}
        </ul>
      ))}
      {result?.lookup.checkInPerson && <div className={styles.warn} role="status">{t("checkInPerson")}</div>}
    </section>
  );
}

function MatchRow({ match, phone, onLinked }: { match: Match; phone: string; onLinked: (patientId: string) => void }) {
  const t = useTranslations("shell.brandDirectory");
  const [dob, setDob] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [keys] = useState(() => intentKeys()); // one Idempotency-Key per link intent, reused if the same request is re-sent
  const today = clinicToday();

  async function link() {
    if (busy || !dobOk(dob, today)) return;
    setBusy(true);
    setProblem(null);
    const body = linkBody(match, phone, dob);
    try {
      const r = await api.post<{ patientId: string }>("/patients/brand-links", body, { idempotencyKey: keys.get("link", body) });
      keys.done("link");
      onLinked(r.patientId);
    } catch (e) {
      const status = e instanceof ApiError ? e.status : 0;
      if (status > 0 && status < 500) keys.done("link"); // a definitive answer: the next try is a new intent; a timeout or 5xx re-sends the same key
      setProblem(t(`problem.${status === 0 || status >= 500 ? "failed" : linkProblem(status, (e as ApiError).code)}`));
    } finally {
      setBusy(false);
    }
  }

  return (
    <li className={styles.match}>
      <div className={styles.who}>
        <span className={styles.badge}>{t("registeredAt", { branch: match.branchLabel })}</span>
        <span className={styles.name}>{match.displayName}</span>
        <span className={styles.muted}>{t(sexKey(match.sex))} · {t("bornIn", { year: match.birthYear })}</span>
        {problem && <span className={styles.error} role="alert">{problem}</span>}
      </div>
      <div className={styles.field}>
        <label className={styles.muted} htmlFor={`bd-dob-${match.sourcePatientRef.slice(0, 8)}`}>{t("dob")}</label>
        <input id={`bd-dob-${match.sourcePatientRef.slice(0, 8)}`} type="date" className={styles.input} value={dob} max={today} onChange={(e) => setDob(e.target.value)} />
      </div>
      <button type="button" className={styles.btn} disabled={busy || !dobOk(dob, today)} onClick={() => void link()}>{busy ? t("linking") : t("link")}</button>
    </li>
  );
}
