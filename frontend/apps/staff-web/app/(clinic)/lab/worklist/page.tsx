"use client";

import { useEffect, useState } from "react";
import AccessGate from "../../../components/ui/AccessGate";
import styles from "./worklist.module.css";

interface WorklistOrder {
  id: string;
  patientId: string;
  priority: "stat" | "urgent" | "routine";
  status: "ordered" | "collected" | "in_progress" | "resulted" | "validated";
  source: "consult" | "walk_in" | "referral" | "partner";
  orderedAt: string;
  dueAt: string;
  testCount: number;
}

interface Counts {
  ordered: number;
  collected: number;
  inProgress: number;
  resulted: number;
  validated: number;
}

interface Worklist {
  counts: Counts;
  items: WorklistOrder[];
}

export default function LabWorklistPage() {
  return (
    <AccessGate permission="lab:view" surface="Lab Worklist">
      <LabWorklistInner />
    </AccessGate>
  );
}

function LabWorklistInner() {
  const [worklist, setWorklist] = useState<Worklist | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filters, setFilters] = useState({ status: "", priority: "", source: "" });

  const fetchWorklist = async () => {
    try {
      setLoading(true);
      const params = new URLSearchParams();
      if (filters.status) params.append("status", filters.status);
      if (filters.priority) params.append("priority", filters.priority);
      if (filters.source) params.append("source", filters.source);

      const response = await fetch(`/v1/lab/worklist?${params}`, {
        credentials: "include",
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = await response.json();
      setWorklist(data);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load worklist");
      setWorklist(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchWorklist();
    // SSE subscription would go here (realtime updates)
  }, [filters]);

  if (loading) return <div className={styles.page}><p>Loading worklist...</p></div>;
  if (error) return <div className={styles.page}><p className={styles.error}>Error: {error}</p></div>;
  if (!worklist) return <div className={styles.page}><p>No data</p></div>;

  const isOverdue = (dueAt: string) => new Date(dueAt) < new Date();
  const isAmber = (dueAt: string) => {
    const due = new Date(dueAt);
    const now = new Date();
    const percent = (due.getTime() - now.getTime()) / (due.getTime() - new Date().getTime());
    return percent < 0.2 && percent > 0;
  };

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>Lab Worklist</h1>

      <div className={styles.counts}>
        <div className={styles.countCard}>
          <span className={styles.countLabel}>Ordered</span>
          <span className={styles.countValue}>{worklist.counts.ordered}</span>
        </div>
        <div className={styles.countCard}>
          <span className={styles.countLabel}>Collected</span>
          <span className={styles.countValue}>{worklist.counts.collected}</span>
        </div>
        <div className={styles.countCard}>
          <span className={styles.countLabel}>In Progress</span>
          <span className={styles.countValue}>{worklist.counts.inProgress}</span>
        </div>
        <div className={styles.countCard}>
          <span className={styles.countLabel}>Resulted</span>
          <span className={styles.countValue}>{worklist.counts.resulted}</span>
        </div>
        <div className={styles.countCard}>
          <span className={styles.countLabel}>Validated</span>
          <span className={styles.countValue}>{worklist.counts.validated}</span>
        </div>
      </div>

      <div className={styles.filters}>
        <select
          value={filters.status}
          onChange={(e) => setFilters({ ...filters, status: e.target.value })}
          className={styles.filterSelect}
        >
          <option value="">All Status</option>
          <option value="ordered">Ordered</option>
          <option value="collected">Collected</option>
          <option value="in_progress">In Progress</option>
          <option value="resulted">Resulted</option>
          <option value="validated">Validated</option>
        </select>
        <select
          value={filters.priority}
          onChange={(e) => setFilters({ ...filters, priority: e.target.value })}
          className={styles.filterSelect}
        >
          <option value="">All Priorities</option>
          <option value="stat">STAT</option>
          <option value="urgent">Urgent</option>
          <option value="routine">Routine</option>
        </select>
        <select
          value={filters.source}
          onChange={(e) => setFilters({ ...filters, source: e.target.value })}
          className={styles.filterSelect}
        >
          <option value="">All Sources</option>
          <option value="consult">Consult</option>
          <option value="walk_in">Walk-in</option>
          <option value="referral">Referral</option>
          <option value="partner">Partner</option>
        </select>
      </div>

      <div className={styles.orders}>
        {worklist.items.map((order) => (
          <div key={order.id} className={styles.orderRow}>
            <div className={styles.priorityBadge} data-priority={order.priority}>
              {order.priority.toUpperCase()}
            </div>
            <div className={styles.orderInfo}>
              <div className={styles.orderId}>{order.id.substring(0, 8)}...</div>
              <div className={styles.orderMeta}>
                Patient: {order.patientId.substring(0, 8)}... | Tests: {order.testCount} | Source: {order.source}
              </div>
            </div>
            <div className={styles.status}>{order.status}</div>
            <div
              className={styles.dueBadge}
              data-overdue={isOverdue(order.dueAt)}
              data-amber={isAmber(order.dueAt)}
            >
              Due: {new Date(order.dueAt).toLocaleTimeString()}
            </div>
          </div>
        ))}
        {worklist.items.length === 0 && <p className={styles.empty}>No orders match the filters</p>}
      </div>
    </main>
  );
}
