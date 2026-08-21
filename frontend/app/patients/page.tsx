"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./patients.module.css";

// Matches GET /v1/patients and GET /v1/patients/{id} (PatientController).
type Patient = { id: string; mrn: string; name: string; phone: string; dob: string; gender: string; status: string };
type PatientPage = { data: Patient[]; page: { nextCursor: string | null; limit: number } };
type PatientDetail = Patient & {
  allergies: string[];
  chronicConditions: string[];
  activePackages: number;
  outstandingBalance: number;
  lastVisitAt: string | null;
};

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

function age(dob: string): number {
  const d = new Date(dob);
  const now = new Date();
  let a = now.getFullYear() - d.getFullYear();
  if (now.getMonth() < d.getMonth() || (now.getMonth() === d.getMonth() && now.getDate() < d.getDate())) a--;
  return a;
}

export default function PatientsPage() {
  const router = useRouter();
  const [patients, setPatients] = useState<Patient[]>([]);
  const [q, setQ] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const [drawerPatient, setDrawerPatient] = useState<PatientDetail | null>(null);
  const [drawerError, setDrawerError] = useState<string | null>(null);

  const authedFetch = useCallback(
    async (path: string) => {
      const token = localStorage.getItem("nabd_access_token");
      if (!token) {
        router.replace("/login");
        return null;
      }
      const res = await fetch(`${API_BASE}${path}`, { headers: { Authorization: `Bearer ${token}` } });
      if (res.status === 401) {
        localStorage.removeItem("nabd_access_token");
        router.replace("/login");
        return null;
      }
      return res;
    },
    [router]
  );

  const load = useCallback(
    async (query: string) => {
      setLoading(true);
      setError(null);
      try {
        const qs = query ? `?q=${encodeURIComponent(query)}` : "?limit=50";
        const res = await authedFetch(`/patients${qs}`);
        if (!res) return;
        if (res.status === 403) {
          setForbidden(true);
          return;
        }
        if (!res.ok) {
          setError("Couldn't load patients. Try again.");
          return;
        }
        const body: PatientPage = await res.json();
        setPatients(body.data);
      } catch {
        setError("Couldn't reach the server. Check your connection and try again.");
      } finally {
        setLoading(false);
      }
    },
    [authedFetch]
  );

  useEffect(() => {
    void Promise.resolve().then(() => load(""));
  }, [load]);

  async function openDrawer(id: string) {
    setDrawerError(null);
    setDrawerPatient(null);
    const res = await authedFetch(`/patients/${id}`);
    if (!res) return;
    if (!res.ok) {
      setDrawerError("Couldn't load this patient.");
      return;
    }
    setDrawerPatient(await res.json());
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Patients</h1>
          <p className={styles.subtitle}>Search the clinic&apos;s patient directory.</p>
        </div>
      </div>

      {!forbidden && (
        <form className={styles.searchBar} onSubmit={(e) => { e.preventDefault(); load(q); }}>
          <input
            className={styles.input}
            placeholder="Search by name, phone or MRN…"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
        </form>
      )}

      <div className={styles.card}>
        {loading ? (
          <div className={styles.state}>Loading…</div>
        ) : forbidden ? (
          <div className={styles.state}>Your role doesn&apos;t have access to patient records.</div>
        ) : error ? (
          <div className={styles.errorState}>{error}</div>
        ) : patients.length === 0 ? (
          <div className={styles.state}>No patients found.</div>
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr><th>Patient</th><th>Phone</th><th>Age / Gender</th><th>Status</th></tr>
              </thead>
              <tbody>
                {patients.map((p) => (
                  <tr key={p.id} className={styles.row} onClick={() => openDrawer(p.id)}>
                    <td><span className={styles.patientName}>{p.name}</span><span className={styles.mrn}>{p.mrn}</span></td>
                    <td>{p.phone}</td>
                    <td>{age(p.dob)} · {p.gender}</td>
                    <td><span className={`${styles.pill} ${p.status === "active" ? styles.pillActive : styles.pillMerged}`}>{p.status}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {(drawerPatient || drawerError) && (
        <div className={styles.overlay} onClick={() => { setDrawerPatient(null); setDrawerError(null); }}>
          <div className={styles.drawer} onClick={(e) => e.stopPropagation()}>
            {drawerError ? (
              <div className={styles.errorState}>{drawerError}</div>
            ) : drawerPatient && (
              <>
                <div className={styles.drawerHeader}>
                  <div>
                    <h2 className={styles.drawerName}>{drawerPatient.name}</h2>
                    <span className={styles.drawerMeta}>{drawerPatient.mrn} · {age(drawerPatient.dob)} · {drawerPatient.gender}</span>
                  </div>
                  <button className={styles.closeBtn} onClick={() => setDrawerPatient(null)} aria-label="Close">×</button>
                </div>

                <div className={drawerPatient.allergies.length === 0 ? styles.allergyBannerNone : styles.allergyBannerSome}>
                  {drawerPatient.allergies.length === 0 ? "No known allergies" : `⚠ Allergies: ${drawerPatient.allergies.join(", ")}`}
                </div>

                <div className={styles.statGrid}>
                  <div className={styles.section}>
                    <div className={styles.sectionLabel}>Last visit</div>
                    <div className={styles.sectionValue}>{drawerPatient.lastVisitAt ? new Date(drawerPatient.lastVisitAt).toLocaleDateString() : "No visits yet"}</div>
                  </div>
                  <div className={styles.section}>
                    <div className={styles.sectionLabel}>Active packages</div>
                    <div className={styles.sectionValue}>{drawerPatient.activePackages}</div>
                  </div>
                  <div className={styles.section}>
                    <div className={styles.sectionLabel}>Outstanding balance</div>
                    <div className={styles.sectionValue}>{drawerPatient.outstandingBalance.toFixed(2)}</div>
                  </div>
                  <div className={styles.section}>
                    <div className={styles.sectionLabel}>Phone</div>
                    <div className={styles.sectionValue}>{drawerPatient.phone}</div>
                  </div>
                </div>

                <div className={styles.section}>
                  <div className={styles.sectionLabel}>Chronic conditions</div>
                  <div className={styles.sectionValue}>
                    {drawerPatient.chronicConditions.length === 0 ? <span className={styles.muted}>None recorded</span> : drawerPatient.chronicConditions.join(", ")}
                  </div>
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </main>
  );
}
