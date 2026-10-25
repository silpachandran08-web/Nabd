"use client";

// ACC-029: typing a phone number lists the whole family (GET /patients/by-phone). Keys 1-9 pick a row when focus is not in a text field.
import { useEffect, useState } from "react";
import styles from "../arrivals.module.css";
import { api, ApiError } from "../../../lib/api";

export type HouseholdMember = { patientId: string; displayName: string; age: number; sex: string; relation: "guardian" | "child" | "member"; lastVisitAt?: string | null; guardianId?: string | null };

/** At least 7 digits and nothing but phone punctuation: the same shape rule the server's phone search uses. */
export function looksLikePhone(q: string): boolean {
  return /^[+()\d\s-]+$/.test(q.trim()) && q.replace(/\D/g, "").length >= 7;
}

const RELATION: Record<HouseholdMember["relation"], string> = { guardian: "parent / guardian", child: "child", member: "" };
const lastVisit = (iso?: string | null) => (iso ? new Date(iso).toLocaleDateString("en-GB") : "no visit yet");

export default function HouseholdPicker({ phone, onPick }: { phone: string; onPick: (m: HouseholdMember) => void }) {
  const [rows, setRows] = useState<HouseholdMember[] | null>(null);
  const [state, setState] = useState<"loading" | "ok" | "error" | "hidden">("loading");

  useEffect(() => {
    let live = true;
    void Promise.resolve().then(() => { if (live) setState("loading"); });
    const t = setTimeout(() => {
      api.get<{ household: HouseholdMember[] }>(`/patients/by-phone?phone=${encodeURIComponent(phone.trim())}`)
        .then((r) => { if (live) { setRows(r.household); setState("ok"); } })
        .catch((e) => { if (live) setState(e instanceof ApiError && (e.status === 400 || e.status === 403) ? "hidden" : "error"); }); // 400 = not a full number yet, 403 = no patients:view
    }, 150);
    return () => { live = false; clearTimeout(t); };
  }, [phone]);

  useEffect(() => {
    if (state !== "ok" || !rows?.length) return;
    function onKey(e: KeyboardEvent) {
      const el = e.target as HTMLElement | null;
      if (e.ctrlKey || e.metaKey || e.altKey || !/^[1-9]$/.test(e.key) || el?.closest("input, textarea, select, [contenteditable='true']")) return;
      const m = rows?.[Number(e.key) - 1];
      if (m) { e.preventDefault(); onPick(m); }
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [state, rows, onPick]);

  if (state === "hidden") return null;
  if (state === "loading") return <div className={styles.muted} role="status">Looking up the family…</div>;
  if (state === "error") return <div className={styles.formError} role="alert">Couldn&apos;t look up the family. Search by name instead.</div>;
  if (!rows || rows.length === 0) return null;
  return (
    <div className={styles.searchResults} role="group" aria-label="Family with this number">
      {rows.slice(0, 9).map((m, i) => (
        <button type="button" key={m.patientId} className={styles.searchResultRow} onClick={() => onPick(m)} aria-keyshortcuts={String(i + 1)}>
          <kbd aria-hidden="true">{i + 1}</kbd> {m.displayName} · {m.age} y · {m.sex}{RELATION[m.relation] && ` · ${RELATION[m.relation]}`} · last visit {lastVisit(m.lastVisitAt)}
        </button>
      ))}
      <div className={styles.muted}>Press 1-{Math.min(rows.length, 9)} (outside the search box) to pick.</div>
    </div>
  );
}
