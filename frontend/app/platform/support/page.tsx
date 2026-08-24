"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./support.module.css";

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

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

const STATUS_CLASS: Record<string, string> = {
  open: styles.pillOpen,
  in_progress: styles.pillIn_progress,
  resolved: styles.pillResolved,
  closed: styles.pillClosed,
};

const PRIORITY_CLASS: Record<string, string> = {
  low: styles.pillLow,
  normal: styles.pillNormal,
  high: styles.pillHigh,
  urgent: styles.pillUrgent,
};

// One next action per status — mirrors the open -> in_progress -> resolved -> closed chain
// TicketService.ALLOWED_TRANSITIONS enforces server-side; closed is terminal, no action shown.
const NEXT_ACTION: Record<string, { toStatus: string; label: string }> = {
  open: { toStatus: "in_progress", label: "Start" },
  in_progress: { toStatus: "resolved", label: "Resolve" },
  resolved: { toStatus: "closed", label: "Close" },
};

export default function SupportTicketsPage() {
  const router = useRouter();
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [actingOn, setActingOn] = useState<string | null>(null);

  const fetchTickets = useCallback(async () => {
    const token = localStorage.getItem("nabd_platform_access_token");
    if (!token) {
      router.replace("/platform/login");
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`${API_BASE}/platform/support/tickets`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (res.status === 401) {
        localStorage.removeItem("nabd_platform_access_token");
        router.replace("/platform/login");
        return;
      }
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
  }, [router]);

  useEffect(() => {
    // Deferred to a microtask so the effect body itself never calls setState
    // synchronously (react-hooks/set-state-in-effect) — fetchTickets does, immediately.
    void Promise.resolve().then(fetchTickets);
  }, [fetchTickets]);

  async function act(id: string, toStatus: string) {
    const token = localStorage.getItem("nabd_platform_access_token");
    if (!token) return;
    setActingOn(id);
    try {
      const res = await fetch(`${API_BASE}/platform/support/tickets/${id}/transitions`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
        body: JSON.stringify({ toStatus }),
      });
      if (res.ok) {
        const updated: Ticket = await res.json();
        setTickets((prev) => prev.map((t) => (t.id === id ? updated : t)));
      }
    } finally {
      setActingOn(null);
    }
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Support tickets</h1>
          <p className={styles.subtitle}>Raised by clinic owners, staff and doctors — breached SLAs sort first.</p>
        </div>
        {!loading && !error && !forbidden && <span className={styles.count}>{tickets.length} total</span>}
      </div>

      <div className={styles.card}>
        {loading ? (
          <div className={styles.state}>Loading…</div>
        ) : forbidden ? (
          <div className={styles.state}>Your role doesn&apos;t have access to support tickets.</div>
        ) : error ? (
          <div className={styles.errorState}>{error}</div>
        ) : tickets.length === 0 ? (
          <div className={styles.state}>No tickets raised yet.</div>
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
                        <span className={`${styles.pill} ${PRIORITY_CLASS[t.priority] ?? ""}`}>{t.priority}</span>
                      </td>
                      <td>
                        <span className={`${styles.pill} ${STATUS_CLASS[t.status] ?? ""}`}>{t.status}</span>
                      </td>
                      <td className={t.slaBreached ? styles.slaBreached : styles.slaDue}>
                        {new Date(t.slaDueAt).toLocaleString()}
                        {t.slaBreached ? " · breached" : ""}
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
