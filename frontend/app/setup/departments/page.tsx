"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./departments.module.css";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

type Department = { id: string; name: string; isDefault: boolean; active: boolean };
type TransferEdge = { fromDepartmentId: string; toDepartmentId: string };
type Problem = { title: string; detail: string };
// Matches GET/POST /v1/departments/{id}/workflow (DepartmentController) — platform-authored
// templates (NB-357) replaced the free-reorder flow editor (NB-355).
type WorkflowTemplate = { code: string; name: string; steps: string[]; toggleKeys: string[] };
type DepartmentWorkflow = { templateCode: string | null; toggles: Record<string, boolean>; resolvedSteps: string[]; availableTemplates: WorkflowTemplate[] };
type Builder = {
  mode: "create" | "edit"; sourceId?: string; name: string; active: boolean;
  availableTemplates: WorkflowTemplate[]; workflowLoading: boolean;
  selectedTemplateCode: string; toggles: Record<string, boolean>;
};

const STEP_LABELS: Record<string, string> = { billing: "Billing", vitals: "Vitals", consultation: "Consultation", procedures: "Procedures" };
const TOGGLE_LABELS: Record<string, string> = { vitals_enabled: "Include a vitals stage" };

function resolvedPreview(template: WorkflowTemplate | undefined, toggles: Record<string, boolean>): string[] {
  if (!template) return [];
  return template.steps.filter((s) => !(s === "vitals" && toggles.vitals_enabled === false));
}

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
    setBuilder({ mode: "create", name: "", active: true, availableTemplates: [], workflowLoading: false, selectedTemplateCode: "", toggles: {} });
  }

  async function openEdit(d: Department) {
    setBuilderError(null);
    setBuilder({ mode: "edit", sourceId: d.id, name: d.name, active: d.active, availableTemplates: [], workflowLoading: true, selectedTemplateCode: "", toggles: {} });
    const res = await authedFetch(`/departments/${d.id}/workflow`);
    if (!res?.ok) {
      setBuilder((prev) => (prev ? { ...prev, workflowLoading: false } : prev));
      return;
    }
    const workflow: DepartmentWorkflow = await res.json();
    setBuilder((prev) =>
      prev
        ? {
            ...prev,
            availableTemplates: workflow.availableTemplates,
            // Nothing picked yet -> the platform default is already running (clinic_walkin, vitals
            // on) — show that as the starting selection rather than an empty picker.
            selectedTemplateCode: workflow.templateCode ?? "clinic_walkin",
            toggles: workflow.toggles ?? {},
            workflowLoading: false,
          }
        : prev
    );
  }

  function selectTemplate(code: string) {
    setBuilder((prev) => (prev ? { ...prev, selectedTemplateCode: code, toggles: {} } : prev));
  }

  function setToggle(key: string, value: boolean) {
    setBuilder((prev) => (prev ? { ...prev, toggles: { ...prev.toggles, [key]: value } } : prev));
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
    if (builder.mode === "edit" && !builder.selectedTemplateCode) {
      setBuilderError("Pick a workflow template.");
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
        const workflowRes = await authedFetch(`/departments/${saved.id}/workflow`, {
          method: "POST",
          body: JSON.stringify({ templateCode: builder.selectedTemplateCode, toggles: builder.toggles }),
        });
        if (!workflowRes?.ok) {
          const p: Problem = await workflowRes?.json().catch(() => ({ title: "Error", detail: "Couldn't save the workflow." }));
          setBuilderError(p?.detail || "Couldn't save the workflow.");
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

  const selectedTemplate = builder ? builder.availableTemplates.find((t) => t.code === builder.selectedTemplateCode) : undefined;

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
                <label className={styles.label}>Workflow template</label>
                <p className={styles.hint}>
                  Pick the stage sequence a visit follows here. Templates are published by the Nabd team — you choose one and flip the toggles it offers.
                </p>
                {builder.workflowLoading ? (
                  <div className={styles.hint}>Loading…</div>
                ) : (
                  <>
                    <select className={styles.flowStaffingSelect} value={builder.selectedTemplateCode} onChange={(e) => selectTemplate(e.target.value)}>
                      {builder.availableTemplates.map((t) => (
                        <option key={t.code} value={t.code}>{t.name}</option>
                      ))}
                    </select>

                    {selectedTemplate && selectedTemplate.toggleKeys.length > 0 && (
                      <div className={styles.flowList}>
                        {selectedTemplate.toggleKeys.map((key) => (
                          <label key={key} className={styles.checkboxRow}>
                            <input
                              type="checkbox"
                              checked={builder.toggles[key] !== false}
                              onChange={(e) => setToggle(key, e.target.checked)}
                            />
                            {TOGGLE_LABELS[key] ?? key}
                          </label>
                        ))}
                      </div>
                    )}

                    <div className={styles.flowList}>
                      {resolvedPreview(selectedTemplate, builder.toggles).map((stepType, i) => (
                        <div key={stepType} className={styles.flowRow}>
                          <span className={styles.flowStepName}>{i + 1}. {STEP_LABELS[stepType] ?? stepType}</span>
                        </div>
                      ))}
                    </div>
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
