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
// Matches GET /v1/platform/tenants/summary (FleetController) — the header's KPI grid.
type FleetSummary = { total: number; byStatus: Record<string, number>; regions: string[] };
// Matches GET /v1/platform/auth/me (PlatformAuthController) — just enough to gate the
// Support access column/tile, same shape platform/page.tsx already reads in full.
type OperatorProfile = { permissions: string[] };
// Matches GET /v1/platform/support-access/grants (SupportAccessController) — active count only.
type SupportGrant = { active: boolean };

const REGION_NAMES: Record<string, string> = { IN: "India", KSA: "Saudi Arabia" };

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
  const [summary, setSummary] = useState<FleetSummary | null>(null);
  const [canRequestSupportAccess, setCanRequestSupportAccess] = useState(false);
  const [activeSupportSessions, setActiveSupportSessions] = useState<number | null>(null);

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

  // Separate from fetchPage on purpose: these three feed the KPI grid, not the paginated table,
  // and "Load more" must never re-fetch them. Each is independently optional — a role that can see
  // Fleet but lacks support_access:view (billing/commercial/compliance_dpo, per NB-257's matrix)
  // still gets a working page, just with that one tile/column showing "not available" instead of 403ing.
  useEffect(() => {
    void (async () => {
      const token = localStorage.getItem("nabd_platform_access_token");
      if (!token) return;
      const headers = { Authorization: `Bearer ${token}` };

      const summaryRes = await fetch(`${API_BASE}/platform/tenants/summary`, { headers }).catch(() => null);
      if (summaryRes?.ok) setSummary(await summaryRes.json());

      const meRes = await fetch(`${API_BASE}/platform/auth/me`, { headers }).catch(() => null);
      if (!meRes?.ok) return;
      const me: OperatorProfile = await meRes.json();
      const allowed = me.permissions.includes("support_access:view");
      setCanRequestSupportAccess(allowed);
      if (!allowed) return;

      const grantsRes = await fetch(`${API_BASE}/platform/support-access/grants`, { headers }).catch(() => null);
      if (grantsRes?.ok) {
        const grants: SupportGrant[] = await grantsRes.json();
        setActiveSupportSessions(grants.filter((g) => g.active).length);
      }
    })();
  }, []);

  function requestSupportAccessFor(tenantId: string) {
    sessionStorage.setItem("nabd_fleet_support_access_tenant_id", tenantId);
    router.push("/platform/access");
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Clinic fleet</h1>
          <p className={styles.subtitle}>Every clinic provisioned on the platform.</p>
        </div>
        {!loading && !error && !forbidden && <span className={styles.count}>{rows.length} loaded</span>}
      </div>

      {!loading && !error && !forbidden && (
        <div className={styles.kpiGrid}>
          <div className={styles.kpiTile}>
            <div className={styles.kpiLabel}>Tenants live</div>
            <div className={styles.kpiValue}>{summary ? summary.total : "—"}</div>
            <div className={styles.kpiSub}>
              {summary ? `${summary.byStatus.provisioning ?? 0} provisioning · ${summary.byStatus.suspended ?? 0} suspended` : ""}
            </div>
          </div>
          <div className={styles.kpiTile}>
            <div className={styles.kpiLabel}>Regions</div>
            <div className={styles.kpiValue}>{summary ? summary.regions.length : "—"}</div>
            <div className={styles.kpiSub}>{summary ? summary.regions.map((r) => REGION_NAMES[r] ?? r).join(" · ") : ""}</div>
          </div>
          <div className={`${styles.kpiTile} ${styles.kpiUnavailable}`}>
            <div className={styles.kpiLabel}>WhatsApp sends 24h</div>
            <div className={styles.kpiValue}>—</div>
            <div className={styles.kpiSub}>Messaging infrastructure (E17) not built yet</div>
          </div>
          <div className={`${styles.kpiTile} ${canRequestSupportAccess ? "" : styles.kpiUnavailable}`}>
            <div className={styles.kpiLabel}>Support sessions</div>
            <div className={styles.kpiValue}>{canRequestSupportAccess ? activeSupportSessions ?? "—" : "—"}</div>
            <div className={styles.kpiSub}>{canRequestSupportAccess ? "All time-limited & logged" : "Needs support access permission"}</div>
          </div>
        </div>
      )}

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
                    {canRequestSupportAccess && <th></th>}
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
                      {canRequestSupportAccess && (
                        <td>
                          <button className={styles.actionBtn} onClick={() => requestSupportAccessFor(t.id)}>Support access</button>
                        </td>
                      )}
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
