"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./departments.module.css";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

type Department = { id: string; name: string; isDefault: boolean; active: boolean };
type TransferEdge = { fromDepartmentId: string; toDepartmentId: string };
type Problem = { title: string; detail: string };
type FlowStep = { stepType: string; staffingDepartmentId: string | null };
type Builder = { mode: "create" | "edit"; sourceId?: string; name: string; active: boolean; flowSteps: FlowStep[]; flowLoading: boolean };

const STEP_TYPES = ["billing", "vitals", "consultation", "procedures"] as const;
const STEP_LABELS: Record<string, string> = { billing: "Billing", vitals: "Vitals", consultation: "Consultation", procedures: "Procedures" };
// Matches DepartmentService's own fallback when nothing's configured yet — the flow editor shows
// this instead of an empty list so it always reflects what's actually happening.
const DEFAULT_FLOW: FlowStep[] = [{ stepType: "vitals", staffingDepartmentId: null }, { stepType: "consultation", staffingDepartmentId: null }];

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
    setBuilder({ mode: "create", name: "", active: true, flowSteps: [], flowLoading: false });
  }

  async function openEdit(d: Department) {
    setBuilderError(null);
    setBuilder({ mode: "edit", sourceId: d.id, name: d.name, active: d.active, flowSteps: [], flowLoading: true });
    const res = await authedFetch(`/departments/${d.id}/flow`);
    if (!res?.ok) {
      setBuilder((prev) => (prev ? { ...prev, flowSteps: DEFAULT_FLOW, flowLoading: false } : prev));
      return;
    }
    const steps: FlowStep[] = await res.json();
    setBuilder((prev) => (prev ? { ...prev, flowSteps: steps.length > 0 ? steps : DEFAULT_FLOW, flowLoading: false } : prev));
  }

  function addFlowStep(stepType: string) {
    setBuilder((prev) => (prev ? { ...prev, flowSteps: [...prev.flowSteps, { stepType, staffingDepartmentId: null }] } : prev));
  }

  function removeFlowStep(stepType: string) {
    setBuilder((prev) => (prev ? { ...prev, flowSteps: prev.flowSteps.filter((s) => s.stepType !== stepType) } : prev));
  }

  function moveFlowStep(index: number, direction: -1 | 1) {
    setBuilder((prev) => {
      if (!prev) return prev;
      const steps = [...prev.flowSteps];
      const target = index + direction;
      if (target < 0 || target >= steps.length) return prev;
      [steps[index], steps[target]] = [steps[target], steps[index]];
      return { ...prev, flowSteps: steps };
    });
  }

  function setFlowStepStaffing(stepType: string, staffingDepartmentId: string) {
    setBuilder((prev) =>
      prev
        ? { ...prev, flowSteps: prev.flowSteps.map((s) => (s.stepType === stepType ? { ...s, staffingDepartmentId: staffingDepartmentId || null } : s)) }
        : prev
    );
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
    if (builder.mode === "edit" && !builder.flowSteps.some((s) => s.stepType === "consultation")) {
      setBuilderError("The flow must include a consultation step.");
      return;
    }
    setBuilderSubmitting(true);
    try {
      const isEdit = builder.mode === "edit";
      const res = await authedFetch(isEdit ? `/departments/${builder.sourceId}` : "/departments", {
        method: isEdit ? "PATCH" : "POST",
        body: JSON.stringify({ name, active: builder.active }),
      });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't save the department." }));
        setBuilderError(p.detail || "Couldn't save the department.");
        return;
      }
      const saved: Department = await res.json();

      if (isEdit) {
        const flowRes = await authedFetch(`/departments/${saved.id}/flow`, {
          method: "POST",
          body: JSON.stringify({ steps: builder.flowSteps.map((s) => ({ stepType: s.stepType, staffingDepartmentId: s.staffingDepartmentId })) }),
        });
        if (!flowRes?.ok) {
          const p: Problem = await flowRes?.json().catch(() => ({ title: "Error", detail: "Couldn't save the visit flow." }));
          setBuilderError(p?.detail || "Couldn't save the visit flow.");
          return;
        }
      }

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
      const res = await authedFetch("/departments/transfers", { method: "POST", body: JSON.stringify({ edges }) });
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

  const availableStepTypes = builder ? STEP_TYPES.filter((t) => !builder.flowSteps.some((s) => s.stepType === t)) : [];

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
            <tr><th>Name</th><th>Status</th><th></th></tr>
          </thead>
          <tbody>
            {departments.map((d) => (
              <tr key={d.id}>
                <td>{d.name}{d.isDefault && <span className={styles.defaultTag}>Default</span>}</td>
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

            {builder.mode === "edit" && (
              <div className={styles.field}>
                <label className={styles.label}>Visit flow</label>
                <p className={styles.hint}>The order a patient moves through this department&apos;s stages. Consultation is always included.</p>
                {builder.flowLoading ? (
                  <div className={styles.hint}>Loading…</div>
                ) : (
                  <>
                    <div className={styles.flowList}>
                      {builder.flowSteps.map((step, i) => (
                        <div key={step.stepType} className={styles.flowRow}>
                          <span className={styles.flowStepName}>{STEP_LABELS[step.stepType]}</span>
                          <select
                            className={styles.flowStaffingSelect}
                            value={step.staffingDepartmentId ?? ""}
                            onChange={(e) => setFlowStepStaffing(step.stepType, e.target.value)}
                          >
                            <option value="">Staffed by…</option>
                            {departments.map((d) => (
                              <option key={d.id} value={d.id}>{d.name}</option>
                            ))}
                          </select>
                          <div className={styles.flowRowActions}>
                            <button type="button" className={styles.smallBtn} onClick={() => moveFlowStep(i, -1)} disabled={i === 0}>▲</button>
                            <button type="button" className={styles.smallBtn} onClick={() => moveFlowStep(i, 1)} disabled={i === builder.flowSteps.length - 1}>▼</button>
                            {step.stepType !== "consultation" && (
                              <button type="button" className={styles.smallBtn} onClick={() => removeFlowStep(step.stepType)}>Remove</button>
                            )}
                          </div>
                        </div>
                      ))}
                    </div>
                    {availableStepTypes.length > 0 && (
                      <div className={styles.flowAddRow}>
                        {availableStepTypes.map((t) => (
                          <button type="button" key={t} className={styles.smallBtn} onClick={() => addFlowStep(t)}>+ {STEP_LABELS[t]}</button>
                        ))}
                      </div>
                    )}
                  </>
                )}
              </div>
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
