"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "./support.module.css";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import RoleDenied from "../../components/platform/RoleDenied";
import StatusPill from "../../components/ui/StatusPill";
import { PriorityPill, TicketStatusPill } from "../../components/platform/statusPills";
import { problemDetail, usePlatformFetch } from "../platformApi";

// Matches GET /v1/platform/support/tickets (TicketAdminController) — a bare array, no cursor
// pagination like fleet's list; ticket volume doesn't need it yet.
type Ticket = {
  id: string;
  tenantId: string;
  tenantName: string;
  tenantSlug: string;
  source: string;
  raisedByName: string;
  raisedByEmail: string | null;
  raisedByRole: string;
  subject: string;
  description: string;
  priority: string;
  status: string;
  slaDueAt: string;
  slaBreached: boolean;
  resolvedAt: string | null;
  createdAt: string;
};

// One next action per status — mirrors the open -> in_progress -> resolved -> closed chain
// TicketService.ALLOWED_TRANSITIONS enforces server-side; closed is terminal, no action shown.
const NEXT_ACTION: Record<string, { toStatus: string; label: string }> = {
  open: { toStatus: "in_progress", label: "Start" },
  in_progress: { toStatus: "resolved", label: "Resolve" },
  resolved: { toStatus: "closed", label: "Close" },
};

// PLT-057: how owners answered the access requests (GET /platform/support-access/grants). Support never sees clinical fields.
type AccessStats = { total: number; approved: number; declined: number; expired: number; waiting: number };

function statsOf(grants: { status: string; windowStartedAt: string | null }[]): AccessStats {
  const n = (f: (g: { status: string; windowStartedAt: string | null }) => boolean) => grants.filter(f).length;
  return {
    total: grants.length,
    approved: n((g) => g.windowStartedAt !== null), // a window opened, whatever became of it since
    declined: n((g) => g.status === "declined"),
    expired: n((g) => g.status === "expired" && g.windowStartedAt === null), // never answered
    waiting: n((g) => g.status === "requested"),
  };
}

export default function SupportTicketsPage() {
  const fetchAuthed = usePlatformFetch();
  const [stats, setStats] = useState<AccessStats | null>(null);
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [actingOn, setActingOn] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const fetchTickets = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetchAuthed("/platform/support/tickets");
      if (!res) return; // session gone, already redirecting to login
      if (res.status === 403) {
        setForbidden(true);
        return;
      }
      if (!res.ok) {
        setError("Couldn't load tickets. Try again.");
        return;
      }
      setTickets(await res.json());
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [fetchAuthed]);

  useEffect(() => {
    // Absent, not an error, when the role cannot read grants (support_access:view).
    void Promise.resolve().then(async () => {
      try {
        const res = await fetchAuthed("/platform/support-access/grants");
        if (!res || !res.ok) return;
        const grants: { status: string; windowStartedAt: string | null }[] = await res.json();
        setStats(statsOf(grants));
      } catch {
        /* the tickets list is the page; the stats strip just stays hidden */
      }
    });
  }, [fetchAuthed]);

  useEffect(() => {
    // Deferred to a microtask so the effect body itself never calls setState
    // synchronously (react-hooks/set-state-in-effect) — fetchTickets does, immediately.
    void Promise.resolve().then(fetchTickets);
  }, [fetchTickets]);

  async function act(id: string, toStatus: string) {
    setActingOn(id);
    setActionError(null);
    try {
      const res = await fetchAuthed(`/platform/support/tickets/${id}/transitions`, {
        method: "POST",
        body: JSON.stringify({ toStatus }),
      });
      if (!res) return;
      if (!res.ok) {
        setActionError(await problemDetail(res, "Couldn't update the ticket. Try again."));
        return;
      }
      const updated: Ticket = await res.json();
      setTickets((prev) => prev.map((t) => (t.id === id ? updated : t)));
    } catch {
      setActionError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setActingOn(null);
    }
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Support Tickets</h1>
          <p className={styles.subtitle}>Raised by clinic owners, staff and doctors — breached SLAs sort first.</p>
        </div>
        {!loading && !error && !forbidden && <span className={styles.count}>{tickets.length} total</span>}
      </div>

      {stats && (
        <p className={styles.subtitle} data-testid="access-stats">
          Clinical fields shown: 0 · Access requests: {stats.total} · Approved by owner: {stats.approved} · Declined: {stats.declined} · Expired unanswered: {stats.expired} · Waiting: {stats.waiting}
        </p>
      )}

      {actionError && <div className={styles.actionError} role="alert">{actionError}</div>}

      <div className={styles.card}>
        {loading ? (
          <Skeleton rows={6} label="Loading tickets" />
        ) : forbidden ? (
          <RoleDenied surface="Support tickets" />
        ) : error ? (
          <ErrorState message={error} onRetry={fetchTickets} />
        ) : tickets.length === 0 ? (
          <EmptyState icon="inbox" title="No tickets raised yet" hint="Tickets raised by clinic staff land here, breached SLAs first." />
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>Ticket</th>
                  <th>Clinic</th>
                  <th>Raised by</th>
                  <th>Priority</th>
                  <th>Status</th>
                  <th>SLA due</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {tickets.map((t) => {
                  const next = NEXT_ACTION[t.status];
                  return (
                    <tr key={t.id}>
                      <td className={styles.subject}>{t.subject}</td>
                      <td>
                        <span className={styles.tenantName}>{t.tenantName}</span>
                        <span className={styles.tenantSlug}>{t.tenantSlug}</span>
                      </td>
                      <td>
                        {t.raisedByName} <span className={styles.muted}>({t.raisedByRole})</span>
                      </td>
                      <td>
                        <PriorityPill priority={t.priority} />
                      </td>
                      <td>
                        <TicketStatusPill status={t.status} />
                      </td>
                      <td className={t.slaBreached ? styles.slaBreached : styles.slaDue}>
                        {new Date(t.slaDueAt).toLocaleString()}
                        {t.slaBreached && <span className={styles.slaPill}><StatusPill tone="danger" label="SLA breached" /></span>}
                      </td>
                      <td>
                        {next && (
                          <div className={styles.actions}>
                            <button
                              className={styles.actionBtn}
                              disabled={actingOn === t.id}
                              onClick={() => act(t.id, next.toStatus)}
                            >
                              {actingOn === t.id ? "…" : next.label}
                            </button>
                          </div>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </main>
  );
}
