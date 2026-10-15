"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { useRouter } from "next/navigation";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";
import { RxSetCard, RxSetDeleteDialog, RxSetEditDialog } from "../consult/RxSetParts";
import { useRxSets, type RxSet, type RxSetScope } from "../consult/rxSets";
import type { AuthedFetch } from "../consult/shared";
import styles from "./templates.module.css";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
type Tab = "sets" | "formularies" | "documents";
type Filter = "all" | RxSetScope;
const TABS: { id: Tab; label: string }[] = [
  { id: "sets", label: "Favourite Rx sets" }, { id: "formularies", label: "Formularies" }, { id: "documents", label: "Documents & letterhead" },
];
const FILTERS: { id: Filter; label: string }[] = [
  { id: "all", label: "All" }, { id: "mine", label: "Mine" }, { id: "shared", label: "Shared" }, { id: "standard", label: "Clinic standard" },
];

// DOC-003 (wireframe NabdShell.html "Prescriptions & Templates"). Favourite Rx sets are real (DOC-035 API). Formularies and
// document templates / letterhead have no API and no stored data yet, so those tabs say so instead of showing sample content.
export default function TemplatesScreen() {
  const router = useRouter();
  const authedFetch = useCallback<AuthedFetch>(async (path, init) => {
    const token = localStorage.getItem("nabd_access_token");
    if (!token) { router.replace("/login"); return null; }
    const res = await fetch(`${API_BASE}${path}`, { ...init, headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" } });
    if (res.status === 401) { localStorage.removeItem("nabd_access_token"); router.replace("/login"); return null; }
    return res;
  }, [router]);

  const [tab, setTab] = useState<Tab>("sets");
  return (
    <main className={styles.page}>
      <h1 className={styles.title}>Prescriptions &amp; Templates</h1>
      <p className={styles.subtitle}>Your saved prescription sets, and the clinic&apos;s shared ones.</p>
      <div role="tablist" aria-label="Prescriptions and templates" className={styles.tabs}>
        {TABS.map((t) => (
          <button key={t.id} type="button" role="tab" id={`tp-tab-${t.id}`} aria-selected={tab === t.id} aria-controls={`tp-panel-${t.id}`}
            className={styles.tab} onClick={() => setTab(t.id)}>{t.label}</button>
        ))}
      </div>
      <section role="tabpanel" id={`tp-panel-${tab}`} aria-labelledby={`tp-tab-${tab}`} className={styles.panel}>
        {tab === "sets" && <SetsTab authedFetch={authedFetch} />}
        {tab === "formularies" && (
          <NotAvailable title="Formularies are not available yet"
            body="There is no formulary library in the system yet, so nothing is listed here. Drug suggestions on the prescription pad come from your clinic's pharmacy items and what has been prescribed before." />
        )}
        {tab === "documents" && (
          <NotAvailable title="Document templates and letterhead are not available yet"
            body="Editable document templates and a letterhead are not set up in the system yet, so nothing is listed here. Clinical documents are still written from a visit in the Consultation Workspace." />
        )}
      </section>
    </main>
  );
}

function NotAvailable({ title, body }: { title: string; body: string }) {
  return (
    <div className={styles.unavailable} data-testid="not-available">
      <h2 className={styles.unavailableTitle}><Icon name="info" size={18} /> {title}</h2>
      <StatusPill tone="neutral" icon="clock" label="Not available yet" />
      <p>{body}</p>
    </div>
  );
}

function SetsTab({ authedFetch }: { authedFetch: AuthedFetch }) {
  const { sets, error, reload, update, remove } = useRxSets(authedFetch);
  const [filter, setFilter] = useState<Filter>("all");
  const [editing, setEditing] = useState<RxSet | null>(null);
  const [deleting, setDeleting] = useState<RxSet | null>(null);
  const canEdit = can("clinical:edit");

  if (error) return <ErrorState message={error} onRetry={() => void reload()} />;
  if (sets === null) return <Skeleton rows={4} label="Loading favourite sets" />;

  const countOf = (f: Filter) => (f === "all" ? sets.length : sets.filter((s) => s.scope === f).length);
  const shown = sets.filter((s) => filter === "all" || s.scope === filter);
  const unknownStale = sets.length > 0 && !sets[0].staleCheckAvailable;
  return (
    <>
      <div className={styles.filters} role="group" aria-label="Filter sets">
        {FILTERS.map((f) => (
          <button key={f.id} type="button" className={styles.filter} aria-pressed={filter === f.id} onClick={() => setFilter(f.id)}>
            {f.label} ({countOf(f.id)})
          </button>
        ))}
      </div>
      {shown.length === 0 ? (
        <EmptyState icon="list" title="No matching sets" hint="Save any prescription as a favourite from the prescription pad."
          action={can("clinical:edit") ? <Link href="/consult" className={styles.link}>Open the consultation workspace</Link> : undefined} />
      ) : (
        <ul style={{ listStyle: "none", margin: 0, padding: 0, display: "flex", flexDirection: "column", gap: "var(--nb-space-8)" }}>
          {shown.map((s) => (
            <RxSetCard key={s.id} set={s} onEdit={canEdit ? () => setEditing(s) : undefined} onDelete={canEdit ? () => setDeleting(s) : undefined} />
          ))}
        </ul>
      )}
      {unknownStale && (
        <p className={styles.note}>Stale-medicine checks need active items in the pharmacy list; there are none yet, so no set is flagged.</p>
      )}
      {editing && <RxSetEditDialog set={editing} onSave={(body) => update(editing.id, body)} onClose={() => setEditing(null)} />}
      {deleting && <RxSetDeleteDialog set={deleting} onDelete={() => remove(deleting.id)} onClose={() => setDeleting(null)} />}
    </>
  );
}
