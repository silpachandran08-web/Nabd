"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./workspaces.module.css";
import StatusPill, { type PillTone } from "../../components/ui/StatusPill";
import { ErrorState, Skeleton } from "../../components/ui/StateViews";

// Matches GET /v1/owners/me/workspaces and POST /v1/owners/workspaces/select (OwnerController).
type Clinic = { id: string; name: string; slug: string; region: string; status: string };
type Brand = { id: string; name: string; status: string; clinics: Clinic[] };
type TokenPair = { accessToken: string; refreshToken: string; expiresIn: number };
type Problem = { title: string; detail: string };

const STATUS_TONE: Record<string, PillTone> = {
  active: "ok", trialing: "info", provisioning: "sync", overdue: "warn", suspended: "danger", offboarding: "warn", offboarded: "neutral",
};
const statusLabel = (s: string) => s.charAt(0).toUpperCase() + s.slice(1).replace(/_/g, " ");

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

export default function OwnerWorkspacesPage() {
  const router = useRouter();
  const [brands, setBrands] = useState<Brand[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectingId, setSelectingId] = useState<string | null>(null);
  const [query, setQuery] = useState("");

  const pendingToken = useCallback(() => sessionStorage.getItem("nabd_owner_pending_token"), []);

  const load = useCallback(async () => {
    setError(null);
    const token = pendingToken();
    if (!token) {
      router.replace("/owner/login");
      return;
    }
    setError(null);
    try {
      const res = await fetch(`${API_BASE}/owners/me/workspaces`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (res.status === 401) {
        sessionStorage.removeItem("nabd_owner_pending_token");
        router.replace("/owner/login");
        return;
      }
      if (!res.ok) {
        setError("Couldn't load your clinics. Try again.");
        return;
      }
      const body: { brands: Brand[] } = await res.json();
      setBrands(body.brands);
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [pendingToken, router]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  async function selectClinic(clinicId: string) {
    const token = pendingToken();
    if (!token) {
      router.replace("/owner/login");
      return;
    }
    setSelectingId(clinicId);
    setError(null);
    try {
      const res = await fetch(`${API_BASE}/owners/workspaces/select`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
        body: JSON.stringify({ clinicId }),
      });
      if (res.status === 401) {
        sessionStorage.removeItem("nabd_owner_pending_token");
        router.replace("/owner/login");
        return;
      }
      if (!res.ok) {
        const body: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't enter that clinic." }));
        setError(body.detail || "Couldn't enter that clinic.");
        return;
      }
      const pair: TokenPair = await res.json();
      localStorage.setItem("nabd_access_token", pair.accessToken);
      localStorage.setItem("nabd_refresh_token", pair.refreshToken);
      // Unlike staff PIN login (lib/session's landingPathFor, which guesses a home page from
      // permissions), this caller already knows the identity entering the clinic — the tenant
      // owner — so it goes straight to the owner's Overview instead of matching queue:view first.
      router.replace("/overview");
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setSelectingId(null);
    }
  }

  function signOut() {
    sessionStorage.removeItem("nabd_owner_pending_token");
    router.replace("/owner/login");
  }

  if (loading) {
    return <main className={styles.page}><Skeleton rows={4} label="Loading your clinics" /></main>;
  }
  if (error && brands.length === 0) {
    return <main className={styles.page}><ErrorState message={error} onRetry={() => { setLoading(true); void load(); }} /></main>;
  }

  const q = query.trim().toLowerCase();
  const shown = brands
    .map((b) => ({ ...b, clinics: q ? b.clinics.filter((c) => [c.name, c.slug, c.region, b.name].some((v) => v.toLowerCase().includes(q))) : b.clinics }))
    .filter((b) => !q || b.clinics.length > 0);

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Clinic &amp; branch</h1>
        <p className={styles.subtitle}>Pick a clinic to open. You can come back and pick a different one anytime.</p>
      </div>

      <div className={styles.toolbar}>
        <input type="search" className={styles.search} placeholder="Search clinics…" aria-label="Search clinics"
          value={query} onChange={(e) => setQuery(e.target.value)} />
        <button type="button" className={styles.signOut} onClick={signOut}>Sign out</button>
      </div>

      {error && <div className={styles.errorState} role="alert">{error}</div>}

      {brands.length === 0 ? (
        <div className={styles.state}>No clinics under your account yet.</div>
      ) : shown.length === 0 ? (
        <div className={styles.state}>No clinics match &ldquo;{query}&rdquo;.</div>
      ) : (
        shown.map((b) => (
          <div key={b.id} className={styles.brandCard}>
            <div className={styles.brandHead}>
              <span className={styles.brandName}>{b.name}</span>
              <span className={styles.brandStatus}>{b.status}</span>
            </div>
            {b.clinics.length === 0 ? (
              <div className={styles.state}>No clinics under this brand yet.</div>
            ) : (
              <ul className={styles.clinicList}>
                {b.clinics.map((c) => (
                  <li key={c.id}>
                    <button
                      className={styles.clinicRow}
                      disabled={selectingId !== null}
                      onClick={() => selectClinic(c.id)}
                    >
                      <span>
                        <span className={styles.clinicName}>{c.name}</span>
                        <span className={styles.clinicMeta}>{c.slug} · {c.region}</span>
                      </span>
                      <StatusPill tone={STATUS_TONE[c.status] ?? "neutral"} label={statusLabel(c.status)} />
                      <span className={styles.enterLabel}>{selectingId === c.id ? "Entering…" : "Enter →"}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ))
      )}
    </main>
  );
}
