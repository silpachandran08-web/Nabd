"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./fleet.module.css";

// Matches GET /v1/platform/tenants (api/openapi.yaml has no Platform paths yet — see PlatformAuthController).
type TenantSummary = {
  id: string;
  slug: string;
  name: string;
  region: string;
  status: string;
  brandName: string | null;
  ownerName: string | null;
  ownerEmail: string | null;
  createdAt: string;
};
type FleetPage = { data: TenantSummary[]; page: { nextCursor: string | null; limit: number } };

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

const STATUS_CLASS: Record<string, string> = {
  provisioning: styles.pillProvisioning,
  trialing: styles.pillTrialing,
  active: styles.pillActive,
  overdue: styles.pillOverdue,
  suspended: styles.pillSuspended,
  offboarding: styles.pillOffboarding,
  offboarded: styles.pillOffboarded,
};

export default function FleetPage() {
  const router = useRouter();
  const [rows, setRows] = useState<TenantSummary[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const fetchPage = useCallback(async (cursor: string | null, append: boolean) => {
    const token = localStorage.getItem("nabd_platform_access_token");
    if (!token) {
      router.replace("/platform/login");
      return;
    }
    append ? setLoadingMore(true) : setLoading(true);
    setError(null);
    try {
      const url = new URL(`${API_BASE}/platform/tenants`);
      url.searchParams.set("limit", "50");
      if (cursor) url.searchParams.set("cursor", cursor);
      const res = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });

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
        setError("Couldn't load the fleet. Try again.");
        return;
      }

      const body: FleetPage = await res.json();
      setRows((prev) => (append ? [...prev, ...body.data] : body.data));
      setNextCursor(body.page.nextCursor);
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
      setLoadingMore(false);
    }
  }, [router]);

  useEffect(() => {
    fetchPage(null, false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Clinic fleet</h1>
          <p className={styles.subtitle}>Every clinic provisioned on the platform.</p>
        </div>
        {!loading && !error && !forbidden && <span className={styles.count}>{rows.length} loaded</span>}
      </div>

      <div className={styles.card}>
        {loading ? (
          <div className={styles.state}>Loading…</div>
        ) : forbidden ? (
          <div className={styles.state}>Your role doesn&apos;t have access to the clinic fleet.</div>
        ) : error ? (
          <div className={styles.errorState}>{error}</div>
        ) : rows.length === 0 ? (
          <div className={styles.state}>No clinics provisioned yet.</div>
        ) : (
          <>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>Clinic</th>
                    <th>Region</th>
                    <th>Status</th>
                    <th>Brand</th>
                    <th>Owner</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((t) => (
                    <tr key={t.id}>
                      <td>
                        <span className={styles.tenantName}>{t.name}</span>
                        <span className={styles.tenantSlug}>{t.slug}</span>
                      </td>
                      <td>{t.region}</td>
                      <td>
                        <span className={`${styles.pill} ${STATUS_CLASS[t.status] ?? ""}`}>{t.status}</span>
                      </td>
                      <td className={t.brandName ? undefined : styles.muted}>{t.brandName ?? "—"}</td>
                      <td className={t.ownerEmail ? undefined : styles.muted}>
                        {t.ownerName ? `${t.ownerName} (${t.ownerEmail})` : "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {nextCursor && (
              <div className={styles.footer}>
                <button
                  className={styles.loadMore}
                  disabled={loadingMore}
                  onClick={() => fetchPage(nextCursor, true)}
                >
                  {loadingMore ? "Loading…" : "Load more"}
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </main>
  );
}
