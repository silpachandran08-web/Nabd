"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import styles from "./workspaces.module.css";
import StatusPill, { type PillTone } from "../../components/ui/StatusPill";
import { ErrorState, Skeleton } from "../../components/ui/StateViews";
import { decodeAccessToken, regionLabel, signOut as endClinicSession } from "../../lib/session";

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

// "Last opened" is per device (there is no server-side history): remembered on a successful Enter.
const LAST_CLINIC_KEY = "nabd_owner_last_clinic";
type LastOpened = { id: string; at: string };
function readLastOpened(): LastOpened | null {
  try {
    const v = JSON.parse(localStorage.getItem(LAST_CLINIC_KEY) ?? "null");
    return v && typeof v.id === "string" && typeof v.at === "string" ? v : null;
  } catch {
    return null;
  }
}

/** The clinic session already open in this browser (owner entered it earlier), if any. */
function currentClinicSession(): { tenantId: string; tenantName: string } | null {
  const token = localStorage.getItem("nabd_access_token");
  const claims = token ? decodeAccessToken(token) : null;
  if (!claims?.owner || !claims.tenantId || !claims.exp || claims.exp * 1000 <= Date.now()) return null;
  return { tenantId: claims.tenantId, tenantName: claims.tenantName ?? "your clinic" };
}

export default function OwnerWorkspacesPage() {
  const router = useRouter();
  const [brands, setBrands] = useState<Brand[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectingId, setSelectingId] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [current, setCurrent] = useState<{ tenantId: string; tenantName: string } | null>(null);
  const [lastOpened, setLastOpened] = useState<LastOpened | null>(null);

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
    void Promise.resolve().then(() => {
      setCurrent(currentClinicSession());
      setLastOpened(readLastOpened());
      return load();
    });
  }, [load]);

  async function selectClinic(clinicId: string) {
    // Already inside this clinic: go back to it rather than minting a second session.
    if (current?.tenantId === clinicId) {
      router.push("/overview");
      return;
    }
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
      // Switching from another clinic: end that clinic's session server-side first (best effort).
      await endClinicSession();
      localStorage.setItem("nabd_access_token", pair.accessToken);
      localStorage.setItem("nabd_refresh_token", pair.refreshToken);
      try { localStorage.setItem(LAST_CLINIC_KEY, JSON.stringify({ id: clinicId, at: new Date().toISOString() })); } catch { /* storage full/blocked: not needed to continue */ }
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

  async function signOut() {
    sessionStorage.removeItem("nabd_owner_pending_token");
    await endClinicSession(); // also any clinic session left open in this browser
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
        <button type="button" className={styles.signOut} onClick={() => void signOut()}>Sign out</button>
      </div>

      {current && (
        <Link href="/overview" className={styles.backLink}>← Back to {current.tenantName}</Link>
      )}

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
              <StatusPill tone={STATUS_TONE[b.status] ?? "neutral"} label={statusLabel(b.status)} />
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
                        <span className={styles.clinicName}>
                          {c.name}
                          {current?.tenantId === c.id && <span className={styles.currentTag}>Current</span>}
                        </span>
                        <span className={styles.clinicMeta}>
                          <span className={styles.regionChip}>{regionLabel(c.region)}</span>
                          {c.slug}
                          {lastOpened?.id === c.id && <> · Last opened here {new Date(lastOpened.at).toLocaleDateString()}</>}
                        </span>
                      </span>
                      <StatusPill tone={STATUS_TONE[c.status] ?? "neutral"} label={statusLabel(c.status)} />
                      <span className={styles.enterLabel}>{selectingId === c.id ? "Entering…" : current?.tenantId === c.id ? "Open →" : "Enter →"}</span>
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
