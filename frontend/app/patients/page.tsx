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
type Allergy = { id: string; substance: string; severity: string; reaction: string | null; active: boolean };
type PrescriptionItem = { id: string; drugName: string; dosage: string | null; frequency: string | null; duration: string | null };
type Prescription = { id: string; status: string; signedAt: string | null; items: PrescriptionItem[] };
type Encounter = { queueEntryId: string; occurredAt: string; diagnosis: string | null; assessment: string | null; medications: string | null };
type Tooth = { toothNumber: number; status: string; note: string | null };

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
const FDI_TEETH = [18, 17, 16, 15, 14, 13, 12, 11, 21, 22, 23, 24, 25, 26, 27, 28,
  48, 47, 46, 45, 44, 43, 42, 41, 31, 32, 33, 34, 35, 36, 37, 38];
const TOOTH_STATUSES = ["healthy", "decayed", "filled", "missing", "crown", "root_canal"] as const;

function decodeJwt(token: string): Record<string, unknown> {
  try {
    const payload = token.split(".")[1];
    const b64 = payload.replace(/-/g, "+").replace(/_/g, "/");
    const padded = b64.padEnd(b64.length + ((4 - (b64.length % 4)) % 4), "=");
    return JSON.parse(atob(padded));
  } catch {
    return {};
  }
}

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
  const [drawerTab, setDrawerTab] = useState<"overview" | "dental">("overview");
  const [canDental, setCanDental] = useState(false);

  const [allergies, setAllergies] = useState<Allergy[]>([]);
  const [newAllergy, setNewAllergy] = useState({ substance: "", severity: "moderate", reaction: "" });
  const [allergyBusy, setAllergyBusy] = useState(false);

  const [prescriptions, setPrescriptions] = useState<Prescription[]>([]);
  const [timeline, setTimeline] = useState<Encounter[]>([]);

  const [chart, setChart] = useState<Tooth[]>([]);
  const [chartBusy, setChartBusy] = useState<number | null>(null);

  const authedFetch = useCallback(
    async (path: string, init?: RequestInit) => {
      const token = localStorage.getItem("nabd_access_token");
      if (!token) {
        router.replace("/login");
        return null;
      }
      const res = await fetch(`${API_BASE}${path}`, {
        ...init,
        headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      });
      if (res.status === 401) {
        localStorage.removeItem("nabd_access_token");
        router.replace("/login");
        return null;
      }
      return res;
    },
    [router]
  );

  useEffect(() => {
    const token = localStorage.getItem("nabd_access_token");
    if (!token) return;
    const permissions = decodeJwt(token).permissions;
    void Promise.resolve().then(() => setCanDental(Array.isArray(permissions) && permissions.includes("specialty_dental:view")));
  }, []);

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
    setDrawerTab("overview");
    setAllergies([]);
    setPrescriptions([]);
    setTimeline([]);
    setChart([]);
    setNewAllergy({ substance: "", severity: "moderate", reaction: "" });
    const res = await authedFetch(`/patients/${id}`);
    if (!res) return;
    if (!res.ok) {
      setDrawerError("Couldn't load this patient.");
      return;
    }
    setDrawerPatient(await res.json());
    refreshAllergies(id);

    const rxRes = await authedFetch(`/clinical/patients/${id}/prescriptions`);
    if (rxRes?.ok) setPrescriptions(await rxRes.json());

    const tlRes = await authedFetch(`/clinical/patients/${id}/timeline`);
    if (tlRes?.ok) setTimeline(await tlRes.json());

    if (canDental) {
      const chartRes = await authedFetch(`/specialty/dental/patients/${id}/chart`);
      if (chartRes?.ok) setChart(await chartRes.json());
    }
  }

  async function refreshAllergies(patientId: string) {
    const res = await authedFetch(`/clinical/patients/${patientId}/allergies`);
    if (res?.ok) setAllergies(await res.json());
  }

  async function addAllergy() {
    if (!drawerPatient || !newAllergy.substance.trim()) return;
    setAllergyBusy(true);
    try {
      const res = await authedFetch(`/clinical/patients/${drawerPatient.id}/allergies`, {
        method: "POST",
        body: JSON.stringify(newAllergy),
      });
      if (res?.ok) {
        setNewAllergy({ substance: "", severity: "moderate", reaction: "" });
        refreshAllergies(drawerPatient.id);
        const pRes = await authedFetch(`/patients/${drawerPatient.id}`);
        if (pRes?.ok) setDrawerPatient(await pRes.json());
      }
    } finally {
      setAllergyBusy(false);
    }
  }

  async function deactivateAllergy(id: string) {
    if (!drawerPatient) return;
    const res = await authedFetch(`/clinical/allergies/${id}/deactivate`, { method: "PATCH" });
    if (res?.ok) {
      refreshAllergies(drawerPatient.id);
      const pRes = await authedFetch(`/patients/${drawerPatient.id}`);
      if (pRes?.ok) setDrawerPatient(await pRes.json());
    }
  }

  async function setToothStatus(toothNumber: number, status: string) {
    if (!drawerPatient) return;
    setChartBusy(toothNumber);
    try {
      const res = await authedFetch(`/specialty/dental/patients/${drawerPatient.id}/chart/${toothNumber}`, {
        method: "PATCH",
        body: JSON.stringify({ status }),
      });
      if (res?.ok) {
        const updated: Tooth = await res.json();
        setChart((prev) => [...prev.filter((t) => t.toothNumber !== toothNumber), updated]);
      }
    } finally {
      setChartBusy(null);
    }
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

                {canDental && (
                  <div className={styles.drawerTabs}>
                    <button className={drawerTab === "overview" ? styles.drawerTabActive : styles.drawerTab} onClick={() => setDrawerTab("overview")}>Overview</button>
                    <button className={drawerTab === "dental" ? styles.drawerTabActive : styles.drawerTab} onClick={() => setDrawerTab("dental")}>Dental chart</button>
                  </div>
                )}

                {drawerTab === "dental" && canDental ? (
                  <div className={styles.toothGrid}>
                    {FDI_TEETH.map((n) => {
                      const tooth = chart.find((t) => t.toothNumber === n);
                      return (
                        <div key={n} className={styles.toothCell}>
                          <span className={styles.toothNumber}>{n}</span>
                          <select
                            className={styles.toothSelect}
                            value={tooth?.status ?? "healthy"}
                            disabled={chartBusy === n}
                            onChange={(e) => setToothStatus(n, e.target.value)}
                          >
                            {TOOTH_STATUSES.map((s) => <option key={s} value={s}>{s.replace("_", " ")}</option>)}
                          </select>
                        </div>
                      );
                    })}
                  </div>
                ) : (
                <>
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

                <div className={styles.section}>
                  <div className={styles.sectionLabel}>Allergy register</div>
                  {allergies.length === 0 ? (
                    <div className={styles.muted}>None recorded</div>
                  ) : (
                    allergies.map((a) => (
                      <div key={a.id} className={styles.allergyRow}>
                        <span>{a.substance} · {a.severity}{a.reaction ? ` · ${a.reaction}` : ""}</span>
                        <button className={styles.linkBtn} onClick={() => deactivateAllergy(a.id)}>Remove</button>
                      </div>
                    ))
                  )}
                  <div className={styles.allergyForm}>
                    <input className={styles.smallInput} placeholder="Substance" value={newAllergy.substance}
                      onChange={(e) => setNewAllergy((p) => ({ ...p, substance: e.target.value }))} />
                    <select className={styles.smallInput} value={newAllergy.severity}
                      onChange={(e) => setNewAllergy((p) => ({ ...p, severity: e.target.value }))}>
                      <option value="mild">mild</option>
                      <option value="moderate">moderate</option>
                      <option value="severe">severe</option>
                    </select>
                    <input className={styles.smallInput} placeholder="Reaction (optional)" value={newAllergy.reaction}
                      onChange={(e) => setNewAllergy((p) => ({ ...p, reaction: e.target.value }))} />
                    <button className={styles.linkBtn} disabled={allergyBusy || !newAllergy.substance.trim()} onClick={addAllergy}>Add</button>
                  </div>
                </div>

                <div className={styles.section}>
                  <div className={styles.sectionLabel}>Previous medicines</div>
                  {prescriptions.length === 0 ? (
                    <div className={styles.muted}>None recorded</div>
                  ) : (
                    prescriptions.map((rx) => (
                      <div key={rx.id} className={styles.sectionValue} style={{ marginBottom: "8px" }}>
                        {rx.signedAt && <span className={styles.muted}>{new Date(rx.signedAt).toLocaleDateString()} — </span>}
                        {rx.items.map((i) => i.drugName).join(", ")}
                      </div>
                    ))
                  )}
                </div>

                <div className={styles.section}>
                  <div className={styles.sectionLabel}>Encounter timeline</div>
                  {timeline.length === 0 ? (
                    <div className={styles.muted}>No completed visits yet</div>
                  ) : (
                    timeline.map((e) => (
                      <div key={e.queueEntryId} className={styles.timelineRow}>
                        <span className={styles.muted}>{new Date(e.occurredAt).toLocaleDateString()}</span>
                        <span>{e.diagnosis || e.assessment || "—"}</span>
                        {e.medications && <span className={styles.muted}>{e.medications}</span>}
                      </div>
                    ))
                  )}
                </div>
                </>
                )}
              </>
            )}
          </div>
        </div>
      )}
    </main>
  );
}
