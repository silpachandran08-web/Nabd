"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { EmptyState, ErrorState, Skeleton } from "../components/ui/StateViews";
import { ApiError, api } from "../lib/api";
import { useLiveRefresh } from "../lib/live";
import { tickOf } from "./messageTicks";
import styles from "./patientMessages.module.css";
import pageStyles from "./patients.module.css";

// COM-006: GET /v1/patients/{id}/messages. Only these fields are relied on; extras are ignored.
export type MessageItem = { id: string; purpose?: string; templateName: string; status: string; statusAt: string; failureReason?: string | null };
type Page = { items: MessageItem[]; nextCursor?: string | null };

/** Patient file > Messages: what this patient was sent and whether it arrived. The list refreshes by itself when a
 * delivery status changes (SSE topic "messages"). Without patients:view the endpoint is 403 and the tab shows nothing. */
export default function PatientMessages({ patientId }: { patientId: string }) {
  const [items, setItems] = useState<MessageItem[] | null>(null);
  const [next, setNext] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [denied, setDenied] = useState(false);
  const [more, setMore] = useState(false);
  const seq = useRef(0); // a slow load for an earlier patient never lands in a later one

  const fetchPage = useCallback(async (cursor: string | null): Promise<Page | null> => {
    let body: Partial<Page>;
    try {
      body = await api.get<Partial<Page>>(`/patients/${patientId}/messages${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`);
    } catch (e) {
      if (e instanceof ApiError && e.status === 403) { setDenied(true); return null; }
      throw e;
    }
    return { items: Array.isArray(body.items) ? body.items : [], nextCursor: body.nextCursor ?? null };
  }, [patientId]);

  const load = useCallback(async () => {
    const mine = ++seq.current;
    try {
      const page = await fetchPage(null);
      if (mine !== seq.current || !page) return;
      setFailed(false); setItems(page.items); setNext(page.nextCursor ?? null);
    } catch { if (mine === seq.current) setFailed(true); }
  }, [fetchPage]);

  useEffect(() => { void Promise.resolve().then(load); return () => { seq.current++; }; }, [load]);
  useLiveRefresh(["patient-messages"], load);

  async function loadMore() {
    if (!next) return;
    setMore(true);
    try {
      const page = await fetchPage(next);
      if (page) { setItems((old) => [...(old ?? []), ...page.items]); setNext(page.nextCursor ?? null); }
    } catch { setFailed(true); } finally { setMore(false); }
  }

  if (denied) return null;
  if (failed) return <ErrorState message="Couldn't load this patient's messages." onRetry={() => void load()} />;
  if (items === null) return <Skeleton rows={3} label="Loading messages" />;
  if (items.length === 0) return <EmptyState icon="inbox" title="No messages sent to this patient yet." />;
  return (
    <section aria-label="Messages">
      {items.map((m) => {
        const t = tickOf(m.status);
        const tone = t.tone === "blue" ? styles.tickRead : t.tone === "red" ? styles.tickFailed : "";
        return (
          <div key={m.id} className={styles.row} data-testid="message-row">
            <span className={styles.main}>
              {m.purpose ?? m.templateName}
              <br />
              <span className={styles.meta}>{new Date(m.statusAt).toLocaleString()}</span>
              {m.failureReason && <><br /><span className={styles.reason}>{m.failureReason}</span></>}
            </span>
            <span className={styles.status}>
              <span className={`${styles.tick} ${tone}`} aria-hidden="true" data-tone={t.tone}>{t.glyph}</span>
              {t.label}
            </span>
          </div>
        );
      })}
      {next && <button type="button" className={`${pageStyles.secondaryBtn} ${styles.more}`} disabled={more} onClick={() => void loadMore()}>{more ? "Loading…" : "Load older messages"}</button>}
    </section>
  );
}
