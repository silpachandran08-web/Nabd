"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./workspaces.module.css";

// Matches GET /v1/owners/me/workspaces and POST /v1/owners/workspaces/select (OwnerController).
type Clinic = { id: string; name: string; slug: string; region: string; status: string };
type Brand = { id: string; name: string; status: string; clinics: Clinic[] };
type TokenPair = { accessToken: string; refreshToken: string; expiresIn: number };
type Problem = { title: string; detail: string };

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

export default function OwnerWorkspacesPage() {
  const router = useRouter();
  const [brands, setBrands] = useState<Brand[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectingId, setSelectingId] = useState<string | null>(null);

  const pendingToken = useCallback(() => sessionStorage.getItem("nabd_owner_pending_token"), []);

  const load = useCallback(async () => {
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
      router.replace("/setup");
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setSelectingId(null);
    }
  }

  if (loading) {
    return <main className={styles.page}><div className={styles.state}>Loading…</div></main>;
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Your clinics</h1>
        <p className={styles.subtitle}>Pick a clinic to open. You can come back and pick a different one anytime.</p>
      </div>

      {error && <div className={styles.errorState}>{error}</div>}

      {brands.length === 0 ? (
        <div className={styles.state}>No clinics under your account yet.</div>
      ) : (
        brands.map((b) => (
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
                        <span className={styles.clinicMeta}>{c.slug} · {c.region} · {c.status}</span>
                      </span>
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
