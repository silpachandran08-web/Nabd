"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./fleet.module.css";
import { isForbidden, platformApi, platformError, useOperatorAccess } from "../platformApi";
import Link from "next/link";
import { ErrorState, Skeleton, EmptyState } from "../../components/ui/StateViews";
import RoleDenied from "../../components/platform/RoleDenied";
import { TenantStatusPill } from "../../components/platform/statusPills";
import StatusPill from "../../components/ui/StatusPill";

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
  // PLT-005: privileged identities (Owner role, approve/refund grants) and how many have MFA; target 100%
  privilegedUsers?: number;
  privilegedMfaEnrolled?: number;
  privilegedMfaPercent?: number | null;
};
type FleetPage = { data: TenantSummary[]; page: { nextCursor: string | null; limit: number } };
// Matches GET /v1/platform/tenants/summary (FleetController) — the header's KPI grid.
type FleetSummary = { total: number; live?: number; trialing?: number; byStatus: Record<string, number>; regions: string[] };
// Matches GET /v1/platform/auth/me (PlatformAuthController) — just enough to gate the
// Support access column/tile, same shape platform/page.tsx already reads in full.
type OperatorProfile = { permissions: string[] };
// Matches GET /v1/platform/support-access/grants (SupportAccessController) — active count only.
type SupportGrant = { active: boolean };

const REGION_NAMES: Record<string, string> = { IN: "India", AE: "United Arab Emirates", SA: "Saudi Arabia", QA: "Qatar" };

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
  const canSwitchFeatures = useOperatorAccess().isSuper; // PLT-059: tenant_detail:edit (super_admin)
  const [activeSupportSessions, setActiveSupportSessions] = useState<number | null>(null);

  const fetchPage = useCallback(async (cursor: string | null, append: boolean) => {
    if (append) setLoadingMore(true);
    else setLoading(true);
    setError(null);
    try {
      const params = new URLSearchParams({ limit: "50" });
      if (cursor) params.set("cursor", cursor);
      const body = await platformApi.get<FleetPage>(`/platform/tenants?${params}`);
      setRows((prev) => (append ? [...prev, ...body.data] : body.data));
      setNextCursor(body.page.nextCursor);
    } catch (e) {
      if (isForbidden(e)) setForbidden(true);
      else setError(platformError(e, "Couldn't load the fleet. Try again."));
    } finally {
      setLoading(false);
      setLoadingMore(false);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(() => fetchPage(null, false));
  }, [fetchPage]);

  // Separate from fetchPage on purpose: these three feed the KPI grid, not the paginated table,
  // and "Load more" must never re-fetch them. Each is independently optional — a role that can see
  // Fleet but lacks support_access:view (billing/commercial/compliance_dpo, per NB-257's matrix)
  // still gets a working page, just with that one tile/column showing "not available" instead of 403ing.
  useEffect(() => {
    void (async () => {
      const sum = await platformApi.get<FleetSummary>("/platform/tenants/summary").catch(() => null);
      if (sum) setSummary(sum);

      const me = await platformApi.get<OperatorProfile>("/platform/auth/me").catch(() => null);
      if (!me) return;
      const allowed = me.permissions.includes("support_access:view");
      setCanRequestSupportAccess(allowed);
      if (!allowed) return;

      const grants = await platformApi.get<SupportGrant[]>("/platform/support-access/grants").catch(() => null);
      if (grants) setActiveSupportSessions(grants.filter((g) => g.active).length);
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
          <h1 className={styles.title}>Clinics</h1>
          <p className={styles.subtitle}>Every clinic provisioned on the platform.</p>
        </div>
        {!loading && !error && !forbidden && <span className={styles.count}>{rows.length} loaded</span>}
      </div>

      {!loading && !error && !forbidden && (
        <div className={styles.kpiGrid}>
          <div className={styles.kpiTile}>
            <div className={styles.kpiLabel}>Tenants live</div>
            {/* LIVE-07: live = the server's count (status active); trialing is not live. Overdue counting is a pending decision. */}
            <div className={styles.kpiValue}>{summary ? (summary.live ?? summary.byStatus.active ?? 0) : "—"}</div>
            <div className={styles.kpiSub}>
              {summary ? `${summary.byStatus.overdue ?? 0} overdue · ${summary.byStatus.trialing ?? 0} trialing · ${summary.byStatus.provisioning ?? 0} provisioning · ${summary.byStatus.suspended ?? 0} suspended` : ""}
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
          <Skeleton rows={6} label="Loading clinics" />
        ) : forbidden ? (
          <RoleDenied surface="The clinic fleet" />
        ) : error ? (
          <ErrorState message={error} onRetry={() => fetchPage(null, false)} />
        ) : rows.length === 0 ? (
          <EmptyState icon="building" title="No clinics provisioned yet" hint="Clinics appear here once a provisioning job creates them." />
        ) : (
          <>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th scope="col">Clinic</th>
                    <th scope="col">Region</th>
                    <th scope="col">Status</th>
                    <th scope="col">Brand</th>
                    <th scope="col">Owner</th>
                    <th scope="col">Privileged MFA</th>
                    {canRequestSupportAccess && <th scope="col"></th>}
                    {canSwitchFeatures && <th scope="col"></th>}
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
                        <TenantStatusPill status={t.status} />
                      </td>
                      <td className={t.brandName ? undefined : styles.muted}>{t.brandName ?? "—"}</td>
                      <td className={t.ownerEmail ? undefined : styles.muted}>
                        {t.ownerName ? `${t.ownerName} (${t.ownerEmail})` : "—"}
                      </td>
                      <td data-testid="mfa-percent" className={t.privilegedMfaPercent == null ? styles.muted : undefined}
                        title={t.privilegedUsers ? `${t.privilegedMfaEnrolled ?? 0} of ${t.privilegedUsers} privileged users enrolled` : undefined}>
                        {t.privilegedMfaPercent == null ? "—" : (
                          <StatusPill tone={t.privilegedMfaPercent === 100 ? "ok" : "warn"} label={`${t.privilegedMfaPercent}%`} />
                        )}
                      </td>
                      {canRequestSupportAccess && (
                        <td>
                          <button className={styles.actionBtn} onClick={() => requestSupportAccessFor(t.id)}>Support access</button>
                        </td>
                      )}
                      {canSwitchFeatures && <td><Link className={styles.actionBtn} href={`/platform/fleet/${t.id}/features`}>Features</Link></td>}
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
