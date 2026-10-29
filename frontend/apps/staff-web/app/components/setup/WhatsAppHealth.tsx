"use client";

// COM-022: the owner's WhatsApp health: quality rating, daily send limit, the 7-day block rate against the 1.5% line, throttle mode,
// "what happened" in plain words, and template approvals. Needs setup:view; without it nothing is rendered (no request is made).
import { useCallback, useEffect, useState } from "react";
import styles from "../../(clinic)/setup/setup.module.css";
import { ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";
import { api, reasonText } from "../../lib/api";

type Ev = { at: string; kind: string; text: string };
type Sender = { id: string; kind: string; displayPhone: string | null; displayName: string | null; status: string; rating: string | null; quality: string | null; tier: string | null;
  tierLabel: string | null; throttleMode: string | null; throttleReason: string | null; blockRate24h: number | null; blockRate7d: number[]; throttleLine: number; events: Ev[] };
type Tpl = { id: string; name: string; language: string; category: string; status: string; rejectionReason: string | null };
type Health = { senders: Sender[]; templates: Tpl[] };

const pct = (x: number) => `${(x * 100).toFixed(1)}%`;

function BlockChart({ values, line }: { values: number[]; line: number }) {
  const max = Math.max(line * 2, ...values, 0.001), w = 280, h = 80;
  const y = (v: number) => h - (v / max) * h;
  const pts = values.map((v, i) => `${(i / Math.max(values.length - 1, 1)) * w},${y(v)}`).join(" ");
  return (
    <svg viewBox={`0 0 ${w} ${h + 4}`} role="img" aria-label={`Block rate over the last 7 days, line at ${pct(line)}`} style={{ inlineSize: "100%", maxInlineSize: 420 }}>
      <line x1="0" x2={w} y1={y(line)} y2={y(line)} stroke="var(--nb-danger-500)" strokeDasharray="4 3" />
      <polyline points={pts} fill="none" stroke="var(--nb-text-primary)" strokeWidth="2" />
    </svg>
  );
}

export default function WhatsAppHealth() {
  const allowed = can("setup:view");
  const [h, setH] = useState<Health | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(async () => {
    try { setH(await api.get<Health>("/owner/messaging/health")); setError(null); }
    catch (e) { setError(reasonText(e, "Could not load WhatsApp health.")); }
  }, []);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- initial load; state is set after the awaited fetch
  useEffect(() => { if (allowed) void load(); }, [allowed, load]);

  if (!allowed) return null;
  if (error && !h) return <ErrorState message={error} onRetry={() => void load()} />;
  if (!h) return <Skeleton rows={3} label="Loading WhatsApp health" />;
  if (h.senders.length === 0) return <div className={styles.card}><h2 className={styles.cardTitle}>WhatsApp health</h2><p className={styles.hint}>No WhatsApp number is connected yet.</p></div>;

  return (
    <>
      {h.senders.map((s) => (
        <div className={styles.card} key={s.id}>
          <h2 className={styles.cardTitle}>WhatsApp health{s.displayPhone ? ` · ${s.displayPhone}` : ""}</h2>
          <p className={styles.hint}>Quality: <strong>{s.quality ?? "Unknown"}</strong>{s.tierLabel ? ` · ${s.tierLabel}` : ""} · Sending: <strong>{s.throttleMode ?? "normal"}</strong></p>
          {s.throttleReason && <p className={styles.hint}>{s.throttleReason}</p>}
          <BlockChart values={s.blockRate7d} line={s.throttleLine} />
          <p className={styles.hint}>Last 24 hours: {s.blockRate24h == null ? "no sends yet" : pct(s.blockRate24h)} blocked. Line: {pct(s.throttleLine)}.</p>
          <h3 className={styles.label}>What happened</h3>
          {s.events.length === 0 ? <p className={styles.hint}>Nothing to report in the last 30 days.</p> : (
            <ul>{s.events.map((e, i) => <li key={i}>{new Date(e.at).toLocaleDateString()} · {e.text}</li>)}</ul>
          )}
        </div>
      ))}
      <div className={styles.card}>
        <h2 className={styles.cardTitle}>Template approvals</h2>
        {h.templates.length === 0 ? <p className={styles.hint}>No templates yet.</p> : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead><tr><th scope="col">Template</th><th scope="col">Language</th><th scope="col">Status</th><th scope="col">Reason</th></tr></thead>
              <tbody>{h.templates.map((t) => <tr key={t.id}><td>{t.name}</td><td>{t.language}</td><td>{t.status}</td><td>{t.rejectionReason ?? ""}</td></tr>)}</tbody>
            </table>
          </div>
        )}
      </div>
    </>
  );
}
