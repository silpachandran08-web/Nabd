"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "./territories.module.css";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import RoleDenied from "../../components/platform/RoleDenied";
import { formatMoney } from "@nabd/domain";
import { usePlatformFetch } from "../platformApi";

// Matches GET /v1/platform/territories (TerritoryController). Region-level, not state-level — tenants
// only carry a region (IN/KSA), no state/city field, and there's no aggregator-demand data yet (NB-227).
type PlanMix = { planCode: string; tenantCount: number };
type RegionSummary = {
  region: string;
  clinicCount: number;
  activeClinicCount: number;
  userCount: number;
  mrrCents: number;
  currency: string;
  taxIdTypes: string[];
  planMix: PlanMix[];
  newClinicsLast30d: number;
};

const REGION_NAME: Record<string, string> = { IN: "India", KSA: "Saudi Arabia" };

export default function TerritoriesPage() {
  const fetchAuthed = usePlatformFetch();
  const [regions, setRegions] = useState<RegionSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetchAuthed("/platform/territories");
      if (!res) return; // session gone, already redirecting to login
      if (res.status === 403) {
        setForbidden(true);
        return;
      }
      if (!res.ok) {
        setError("Couldn't load territories. Try again.");
        return;
      }
      setRegions(await res.json());
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [fetchAuthed]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Territories &amp; Coverage</h1>
        <p className={styles.subtitle}>
          Data residency is not a sales territory: it is pinned once per installation and never changes. Territories are purely commercial.
        </p>
      </div>

      {loading ? (
        <Skeleton rows={5} label="Loading territories" />
      ) : forbidden ? (
        <RoleDenied surface="Territories" />
      ) : error ? (
        <ErrorState message={error} onRetry={load} />
      ) : regions.length === 0 ? (
        <EmptyState icon="chart" title="No coverage yet" hint="Regions appear once a clinic is provisioned in them." />
      ) : (
        <div className={styles.grid}>
          {regions.map((r) => (
            <div key={r.region} className={styles.card}>
              <h2 className={styles.regionTitle}>{REGION_NAME[r.region] ?? r.region}</h2>
              <div className={styles.statGrid}>
                <div className={styles.stat}>
                  <span className={styles.statLabel}>Clinics</span>
                  <span className={styles.statValue}>{r.clinicCount}</span>
                </div>
                <div className={styles.stat}>
                  <span className={styles.statLabel}>Active</span>
                  <span className={styles.statValue}>{r.activeClinicCount}</span>
                </div>
                <div className={styles.stat}>
                  <span className={styles.statLabel}>Users</span>
                  <span className={styles.statValue}>{r.userCount}</span>
                </div>
                <div className={styles.stat}>
                  <span className={styles.statLabel}>MRR</span>
                  <span className={styles.statValue}>{formatMoney(r.mrrCents / 100, r.currency)}</span>
                </div>
                <div className={styles.stat}>
                  <span className={styles.statLabel}>New (30d)</span>
                  <span className={styles.statValue}>{r.newClinicsLast30d}</span>
                </div>
              </div>

              <div className={styles.section}>
                <div className={styles.sectionLabel}>Tax codes</div>
                {r.taxIdTypes.length === 0 ? (
                  <span className={styles.muted}>None on file</span>
                ) : (
                  <div className={styles.chips}>
                    {r.taxIdTypes.map((t) => <span key={t} className={styles.chip}>{t}</span>)}
                  </div>
                )}
              </div>

              <div className={styles.section}>
                <div className={styles.sectionLabel}>Plan mix</div>
                {r.planMix.length === 0 ? (
                  <span className={styles.muted}>No subscriptions yet</span>
                ) : (
                  <div className={styles.chips}>
                    {r.planMix.map((m) => <span key={m.planCode} className={styles.chip}>{m.planCode}: {m.tenantCount}</span>)}
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </main>
  );
}
