"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./departments.module.css";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

type Department = { id: string; name: string; requiresVitals: boolean; isDefault: boolean; active: boolean };
type TransferEdge = { fromDepartmentId: string; toDepartmentId: string };
type Problem = { title: string; detail: string };
type Builder = { mode: "create" | "edit"; sourceId?: string; name: string; requiresVitals: boolean; active: boolean };

export default function DepartmentsPage() {
  const router = useRouter();

  const [departments, setDepartments] = useState<Department[]>([]);
  const [edges, setEdges] = useState<TransferEdge[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const [builder, setBuilder] = useState<Builder | null>(null);
  const [builderError, setBuilderError] = useState<string | null>(null);
  const [builderSubmitting, setBuilderSubmitting] = useState(false);

  const [matrixDirty, setMatrixDirty] = useState(false);
  const [matrixError, setMatrixError] = useState<string | null>(null);
  const [savingMatrix, setSavingMatrix] = useState(false);

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

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [depRes, edgeRes] = await Promise.all([authedFetch("/departments"), authedFetch("/departments/transfers")]);
      if (!depRes || !edgeRes) return;
      if (depRes.status === 403 || edgeRes.status === 403) {
        setForbidden(true);
        return;
      }
      if (!depRes.ok || !edgeRes.ok) {
        setError("Couldn't load departments. Try again.");
        return;
      }
      setDepartments(await depRes.json());
      setEdges(await edgeRes.json());
      setMatrixDirty(false);
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  function openCreate() {
    setBuilderError(null);
    setBuilder({ mode: "create", name: "", requiresVitals: true, active: true });
  }

  function openEdit(d: Department) {
    setBuilderError(null);
    setBuilder({ mode: "edit", sourceId: d.id, name: d.name, requiresVitals: d.requiresVitals, active: d.active });
  }

  async function submitBuilder(e: React.FormEvent) {
    e.preventDefault();
    if (!builder) return;
    setBuilderError(null);
    const name = builder.name.trim();
    if (!name) {
      setBuilderError("Name the department.");
      return;
    }
    setBuilderSubmitting(true);
    try {
      const isEdit = builder.mode === "edit";
      const res = await authedFetch(isEdit ? `/departments/${builder.sourceId}` : "/departments", {
        method: isEdit ? "PATCH" : "POST",
        body: JSON.stringify({ name, requiresVitals: builder.requiresVitals, active: builder.active }),
      });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't save the department." }));
        setBuilderError(p.detail || "Couldn't save the department.");
        return;
      }
      const saved: Department = await res.json();
      setDepartments((prev) => (isEdit ? prev.map((d) => (d.id === saved.id ? saved : d)) : [...prev, saved]));
      setBuilder(null);
    } finally {
      setBuilderSubmitting(false);
    }
  }

  function toggleEdge(fromId: string, toId: string) {
    setMatrixDirty(true);
    setEdges((prev) => {
      const exists = prev.some((e) => e.fromDepartmentId === fromId && e.toDepartmentId === toId);
      if (exists) return prev.filter((e) => !(e.fromDepartmentId === fromId && e.toDepartmentId === toId));
      return [...prev, { fromDepartmentId: fromId, toDepartmentId: toId }];
    });
  }

  async function saveMatrix() {
    setMatrixError(null);
    setSavingMatrix(true);
    try {
      const res = await authedFetch("/departments/transfers", { method: "PUT", body: JSON.stringify({ edges }) });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't save the transfer graph." }));
        setMatrixError(p.detail || "Couldn't save the transfer graph.");
        return;
      }
      setEdges(await res.json());
      setMatrixDirty(false);
    } finally {
      setSavingMatrix(false);
    }
  }

  if (loading) return <main className={styles.page}><div className={styles.state}>Loading…</div></main>;
  if (forbidden) return <main className={styles.page}><div className={styles.state}>Your role doesn&apos;t have access to departments.</div></main>;
  if (error) return <main className={styles.page}><div className={styles.errorState}>{error}</div></main>;

  return (
    <main className={styles.page}>
      <div className={styles.strip}>
        <button className={styles.backBtn} onClick={() => router.push("/setup")}>← Clinic Setup</button>
        <span className={styles.stripName}>Departments</span>
      </div>

      <div className={styles.card}>
        <div className={styles.cardHeader}>
          <h2 className={styles.cardTitle}>Departments</h2>
          <button className={styles.btn} onClick={openCreate}>+ Add department</button>
        </div>
        <table className={styles.table}>
          <thead>
            <tr><th>Name</th><th>Requires vitals</th><th>Status</th><th></th></tr>
          </thead>
          <tbody>
            {departments.map((d) => (
              <tr key={d.id}>
                <td>{d.name}{d.isDefault && <span className={styles.defaultTag}>Default</span>}</td>
                <td>{d.requiresVitals ? "Yes" : "No"}</td>
                <td>{d.active ? "Active" : "Inactive"}</td>
                <td><button className={styles.smallBtn} onClick={() => openEdit(d)}>Edit</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className={styles.card}>
        <h2 className={styles.cardTitle}>Transfer graph</h2>
        <p className={styles.hint}>Check a cell to let a patient be transferred from the row department to the column department mid-visit.</p>
        {departments.length < 2 ? (
          <div className={styles.hint}>Add at least two departments to configure transfers.</div>
        ) : (
          <>
            <div className={styles.matrixWrap}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>From \ To</th>
                    {departments.map((to) => <th key={to.id}>{to.name}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {departments.map((from) => (
                    <tr key={from.id}>
                      <td>{from.name}</td>
                      {departments.map((to) =>
                        from.id === to.id ? (
                          <td key={to.id} className={styles.matrixDiagonal}>—</td>
                        ) : (
                          <td key={to.id}>
                            <input
                              type="checkbox"
                              checked={edges.some((e) => e.fromDepartmentId === from.id && e.toDepartmentId === to.id)}
                              onChange={() => toggleEdge(from.id, to.id)}
                            />
                          </td>
                        )
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {matrixError && <div className={styles.errorState}>{matrixError}</div>}
            <button className={styles.btnPrimary} onClick={saveMatrix} disabled={savingMatrix || !matrixDirty}>
              {savingMatrix ? "Saving…" : "Save transfer graph"}
            </button>
          </>
        )}
      </div>

      {builder && (
        <div className={styles.overlay} onClick={() => setBuilder(null)}>
          <form className={styles.modal} onClick={(e) => e.stopPropagation()} onSubmit={submitBuilder}>
            <h2 className={styles.modalTitle}>{builder.mode === "create" ? "New department" : "Edit department"}</h2>
            <div className={styles.field}>
              <label className={styles.label}>Name</label>
              <input className={styles.input} value={builder.name} onChange={(e) => setBuilder({ ...builder, name: e.target.value })} />
            </div>
            <label className={styles.checkboxRow}>
              <input
                type="checkbox"
                checked={builder.requiresVitals}
                onChange={(e) => setBuilder({ ...builder, requiresVitals: e.target.checked })}
              />
              Requires vitals before consultation
            </label>
            {builder.mode === "edit" && (
              <label className={styles.checkboxRow}>
                <input
                  type="checkbox"
                  checked={builder.active}
                  onChange={(e) => setBuilder({ ...builder, active: e.target.checked })}
                  disabled={departments.find((d) => d.id === builder.sourceId)?.isDefault}
                />
                Active{departments.find((d) => d.id === builder.sourceId)?.isDefault ? " (default department, always active)" : ""}
              </label>
            )}
            {builderError && <div className={styles.errorState}>{builderError}</div>}
            <div className={styles.modalActions}>
              <button type="button" className={styles.btn} onClick={() => setBuilder(null)}>Cancel</button>
              <button type="submit" className={styles.btnPrimary} disabled={builderSubmitting}>
                {builderSubmitting ? "Saving…" : "Save"}
              </button>
            </div>
          </form>
        </div>
      )}
    </main>
  );
}
