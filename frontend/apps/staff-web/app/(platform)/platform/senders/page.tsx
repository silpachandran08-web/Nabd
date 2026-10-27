"use client";

// COM-022: every WhatsApp sender of this region with rating, tier, throttle mode and delivered rate. clinics_fleet:view, like the clinics list.
import { useCallback, useEffect, useState } from "react";
import styles from "../fleet/fleet.module.css";
import { isForbidden, platformApi, platformError } from "../platformApi";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/StateViews";
import RoleDenied from "../../../components/platform/RoleDenied";

type Item = { senderAccountId: string; tenantName: string | null; kind: string; displayPhone: string | null; region: string | null; status: string; rating: string | null; tier: string | null; throttleMode: string | null; deliveredRate7d: number | null; sends7d: number };
type Page = { items: Item[]; nextCursor: string | null };

export default function SendersPage() {
  const [rows, setRows] = useState<Item[] | null>(null);
  const [next, setNext] = useState<string | null>(null);
  const [rating, setRating] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const load = useCallback(async (cursor?: string | null) => {
    if (!cursor) setRows(null);
    setError(null);
    try {
      const p = new URLSearchParams({ limit: "50" });
      if (rating) p.set("rating", rating);
      if (cursor) p.set("cursor", cursor);
      const body = await platformApi.get<Page>(`/platform/senders?${p}`);
      setRows((prev) => (cursor ? [...(prev ?? []), ...body.items] : body.items)); setNext(body.nextCursor);
    } catch (e) { if (isForbidden(e)) setForbidden(true); else setError(platformError(e, "Couldn't load the senders. Try again.")); }
  }, [rating]);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- load on filter change; state is set after the awaited fetch
  useEffect(() => { void load(); }, [load]);

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div><h1 className={styles.title}>WhatsApp senders</h1><p className={styles.subtitle}>Quality, tier and throttle mode of every sender.</p></div>
      </div>
      {forbidden ? <RoleDenied surface="WhatsApp senders" /> : (
        <>
          <label htmlFor="sd-rating" style={{ display: "block", fontSize: 12 }}>Rating</label>
          <select id="sd-rating" value={rating} onChange={(e) => setRating(e.target.value)} style={{ minBlockSize: 44, marginBlockEnd: 12 }}>
            <option value="">All</option><option value="GREEN">Green</option><option value="YELLOW">Yellow</option><option value="RED">Red</option>
          </select>
          {error ? <ErrorState message={error} onRetry={() => void load()} /> : !rows ? <Skeleton rows={5} label="Loading senders" /> : rows.length === 0 ? <EmptyState title="No senders" hint="No sender matches this filter." /> : (
            <div style={{ overflowInline: "auto" } as React.CSSProperties}>
              <table style={{ inlineSize: "100%" }}>
                <thead><tr><th scope="col">Clinic</th><th scope="col">Number</th><th scope="col">Rating</th><th scope="col">Tier</th><th scope="col">Throttle</th><th scope="col">Delivered 7d</th><th scope="col">Sends 7d</th></tr></thead>
                <tbody>{rows.map((r) => (
                  <tr key={r.senderAccountId}><td>{r.tenantName ?? "Platform"}</td><td>{r.displayPhone ?? ""}</td><td>{r.rating ?? ""}</td><td>{r.tier ?? ""}</td><td>{r.throttleMode ?? "normal"}</td>
                    <td>{r.deliveredRate7d == null ? "—" : `${(r.deliveredRate7d * 100).toFixed(1)}%`}</td><td>{r.sends7d}</td></tr>
                ))}</tbody>
              </table>
            </div>
          )}
          {next && <button type="button" style={{ minBlockSize: 44, marginBlockStart: 12 }} onClick={() => void load(next)}>Load more</button>}
        </>
      )}
    </main>
  );
}
