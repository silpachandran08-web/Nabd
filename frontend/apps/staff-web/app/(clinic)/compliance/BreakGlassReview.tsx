"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import styles from "./compliance.module.css";
import Dialog from "../../components/ui/Dialog";
import StatusPill from "../../components/ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import { can } from "../../lib/permissions";
import { api, ApiError } from "../../lib/api";
import { overdueGrants, reviewProblem, type Grant } from "./bgReviewRules";

// PLT-013 (prototype 'bgreview'). Matches GET /v1/break-glass/grants, GET /v1/break-glass/grants/{id}/reads,
// POST /v1/break-glass/grants/{id}/review and the owner's reminders from GET /v1/auth/break-glass/alerts.
type Read = { patientId: string; patientRef: string | null; patientName: string | null; resourceType: string; readAt: string };
type Status = "unreviewed" | "reviewed";

const when = (iso: string) => new Date(iso).toLocaleString();

export default function BreakGlassReview() {
  const t = useTranslations("common.breakGlassReview");
  const [status, setStatus] = useState<Status>("unreviewed");
  const [items, setItems] = useState<Grant[] | null>(null);
  const [next, setNext] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reminded, setReminded] = useState<Set<string>>(new Set());
  const [open, setOpen] = useState<Grant | null>(null);
  const [loadedAt, setLoadedAt] = useState(0); // "over 24 h" is judged as of the last load (render must stay pure)

  const load = useCallback(async (s: Status, cursor?: string) => {
    setError(null);
    try {
      const q = new URLSearchParams({ status: s, limit: "50" });
      if (cursor) q.set("cursor", cursor);
      const page = await api.get<{ items: Grant[]; nextCursor: string | null }>(`/break-glass/grants?${q}`);
      setItems((prev) => (cursor ? [...(prev ?? []), ...page.items] : page.items));
      setNext(page.nextCursor);
      setLoadedAt(Date.now());
    } catch {
      setError(t("loadError"));
    }
  }, [t]);

  const loadReminders = useCallback(async () => {
    try {
      const a = await api.get<{ items: { grantId: string; kind?: string }[] }>("/auth/break-glass/alerts");
      setReminded(new Set(a.items.filter((x) => x.kind === "review_reminder").map((x) => x.grantId)));
    } catch { /* the reminder banner is a convenience; the queue itself still shows overdue items */ }
  }, []);

  useEffect(() => { void Promise.resolve().then(() => load(status)); }, [load, status]);
  useEffect(() => { void Promise.resolve().then(loadReminders); }, [loadReminders]);

  const overdue = status === "unreviewed" ? overdueGrants(items ?? [], reminded, loadedAt) : [];

  return (
    <section className={styles.card} aria-labelledby="bg-h">
      <h2 id="bg-h" className={styles.h2}>{t("title")}</h2>
      <p className={styles.muted}>{t("intro")}</p>
      <div className={styles.actions} role="group" aria-label={t("title")}>
        {(["unreviewed", "reviewed"] as const).map((s) => (
          <button key={s} type="button" className={status === s ? styles.primary : styles.secondary} aria-pressed={status === s}
            onClick={() => { setItems(null); setStatus(s); }}>
            {s === "unreviewed" ? t("showUnreviewed") : t("showReviewed")}
          </button>
        ))}
      </div>
      {overdue.length > 0 && <div className={styles.error} role="status">{t("reminder", { count: overdue.length })}</div>}
      {items === null && !error ? <Skeleton rows={3} label={t("loading")} />
        : error ? <ErrorState message={error} onRetry={() => load(status)} />
        : items!.length === 0 ? (status === "unreviewed"
          ? <EmptyState icon="shield" title={t("emptyUnreviewed")} hint={t("emptyUnreviewedHint")} />
          : <EmptyState icon="shield" title={t("emptyReviewed")} />)
        : (
          <>
            {items!.map((g) => (
              <article key={g.grantId} className={styles.req} data-testid="bg-grant">
                <div>
                  <strong>{g.staff.name}</strong>{" "}
                  {g.outcome ? <StatusPill tone={g.outcome === "justified" ? "ok" : "danger"} label={t(`outcomeLabel.${g.outcome}`)} />
                    : overdue.includes(g) && <StatusPill tone="warn" label={t("overdue")} />}
                </div>
                <div className={styles.muted}>{t(`reasons.${g.reasonCode}`)}{g.reasonText ? `: ${g.reasonText}` : ""}</div>
                <div className={styles.muted}>{t("window", { start: when(g.startedAt), end: when(g.endedAt) })} · {t("device", { device: g.deviceLabel ?? "?" })}</div>
                <div className={styles.muted}>{t("reads", { count: g.readsCount })}</div>
                {can("compliance:view") && (
                  <div className={styles.actions}>
                    <button type="button" className={styles.secondary} onClick={() => setOpen(g)}>{g.outcome ? t("view") : t("review")}</button>
                  </div>
                )}
              </article>
            ))}
            {next && <button type="button" className={styles.secondary} onClick={() => load(status, next)}>{t("loadMore")}</button>}
          </>
        )}
      {open && <ReviewDialog grant={open} onClose={() => setOpen(null)} onSaved={() => { setOpen(null); void load(status); void loadReminders(); }} />}
    </section>
  );
}

function ReviewDialog({ grant, onClose, onSaved }: { grant: Grant; onClose: () => void; onSaved: () => void }) {
  const t = useTranslations("common.breakGlassReview");
  const [reads, setReads] = useState<Read[] | null>(null);
  const [readsError, setReadsError] = useState(false);
  const [outcome, setOutcome] = useState<"justified" | "not_justified" | null>(null);
  const [note, setNote] = useState("");
  const [revoke, setRevoke] = useState(false);
  const [busy, setBusy] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const mayDecide = !grant.outcome && can("compliance:approve");
  const mayRevoke = can("staff:edit");

  const loadReads = useCallback(async () => {
    setReadsError(false);
    try {
      setReads((await api.get<{ items: Read[] }>(`/break-glass/grants/${grant.grantId}/reads`)).items);
    } catch {
      setReadsError(true);
    }
  }, [grant.grantId]);
  useEffect(() => { void Promise.resolve().then(loadReads); }, [loadReads]);

  async function save() {
    if (!outcome) return;
    if (outcome === "not_justified" && !note.trim()) { setSaveError(t("noteRequired")); return; }
    setBusy(true);
    setSaveError(null);
    try {
      await api.post(`/break-glass/grants/${grant.grantId}/review`, {
        outcome, note: note.trim() || undefined, revokePermission: outcome === "not_justified" && revoke ? true : undefined,
      });
      onSaved();
    } catch (e) {
      const p = reviewProblem(e instanceof ApiError ? e.status : 0);
      setSaveError(t(p));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title={t("dialogTitle", { name: grant.staff.name })} onClose={onClose} size="lg">
      <div className={styles.dialogBody}>
        <div className={styles.muted}>{t("reason")}: {t(`reasons.${grant.reasonCode}`)}{grant.reasonText ? `: ${grant.reasonText}` : ""}</div>
        <div className={styles.muted}>{t("window", { start: when(grant.startedAt), end: when(grant.endedAt) })} · {t("device", { device: grant.deviceLabel ?? "?" })}</div>
        <h3 className={styles.h3}>{t("timeline")}</h3>
        {reads === null && !readsError ? <Skeleton rows={3} label={t("readsLoading")} />
          : readsError ? <ErrorState message={t("readsError")} onRetry={loadReads} />
          : reads!.length === 0 ? <p className={styles.muted}>{t("noReads")}</p>
          : (
            <ol className={styles.history} aria-label={t("timeline")}>
              {reads!.map((r, i) => (
                <li key={`${r.patientId}-${r.readAt}-${i}`}>
                  {t("readRow", { time: when(r.readAt), patient: r.patientName ?? t("unknownPatient"), ref: r.patientRef ?? "-", type: r.resourceType })}
                </li>
              ))}
            </ol>
          )}
        {saveError && <div className={styles.error} role="alert">{saveError}</div>}
        {mayDecide && (
          <fieldset className={styles.fieldset}>
            <legend className={styles.h3}>{t("outcome")}</legend>
            {(["justified", "not_justified"] as const).map((o) => (
              <label key={o} className={styles.choice}>
                <input type="radio" name="bg-outcome" checked={outcome === o} onChange={() => setOutcome(o)} />
                {o === "justified" ? t("justified") : t("notJustified")}
              </label>
            ))}
            {outcome && (
              <label className={styles.field}>
                <span>{outcome === "not_justified" ? t("incidentNote") : t("noteOptional")}</span>
                {outcome === "not_justified" && <span className={styles.muted}>{t("incidentNoteHint")}</span>}
                <textarea className={styles.textarea} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)}
                  required={outcome === "not_justified"} />
              </label>
            )}
            {outcome === "not_justified" && mayRevoke && (
              <label className={styles.choice}>
                <input type="checkbox" checked={revoke} onChange={(e) => setRevoke(e.target.checked)} />
                {t("revoke", { name: grant.staff.name })}
              </label>
            )}
            <div className={styles.actions}>
              <button type="button" className={styles.primary} disabled={!outcome || busy} onClick={save}>{t("save")}</button>
            </div>
          </fieldset>
        )}
      </div>
    </Dialog>
  );
}
