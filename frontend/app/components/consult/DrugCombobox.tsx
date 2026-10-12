"use client";

import { useCallback, useEffect, useId, useRef, useState } from "react";
import StatusPill from "../ui/StatusPill";
import type { DrugSuggestion } from "./shared";
import s from "./RxPad.module.css";

type AuthedFetch = (path: string, init?: RequestInit) => Promise<Response | null>;
const DEBOUNCE_MS = 250;
const MIN_CHARS = 2;

/** Accessible type-ahead (ARIA 1.2 combobox with list autocomplete) over GET /clinical/drugs/search. Suggestions are the
 * tenant's own pharmacy list and past prescriptions, never a drug database. What the doctor types is always kept as
 * typed: picking a suggestion is optional, Tab/blur never replaces the text, and a failed search never blocks entry. */
export default function DrugCombobox({
  id, label, value, placeholder, disabled, authedFetch, onType, onPick, hint,
}: {
  id: string; label: string; value: string; placeholder?: string; disabled?: boolean; hint?: string;
  authedFetch: AuthedFetch;
  /** Every keystroke (free text). */
  onType: (text: string) => void;
  /** A suggestion was chosen: its name replaces the field text. */
  onPick: (name: string) => void;
}) {
  const uid = useId();
  const listId = `${uid}-list`;
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<DrugSuggestion[]>([]);
  const [state, setState] = useState<"idle" | "loading" | "done" | "error">("idle");
  const [active, setActive] = useState(-1);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const ctrl = useRef<AbortController | null>(null);
  const seq = useRef(0);

  const cancel = useCallback(() => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
    ctrl.current?.abort();
    ctrl.current = null;
    seq.current += 1;
  }, []);
  useEffect(() => cancel, [cancel]);

  function search(text: string) {
    cancel();
    const q = text.trim();
    if (q.length < MIN_CHARS) {
      setOpen(false); setState("idle"); setItems([]); setActive(-1);
      return;
    }
    setOpen(true); setState("loading"); setActive(-1);
    const mine = seq.current;
    timer.current = setTimeout(async () => {
      const c = new AbortController();
      ctrl.current = c;
      try {
        const res = await authedFetch(`/clinical/drugs/search?q=${encodeURIComponent(q)}&limit=8`, { signal: c.signal });
        if (mine !== seq.current) return;
        if (!res || !res.ok) { setState("error"); setItems([]); return; }
        const data: DrugSuggestion[] = await res.json();
        if (mine !== seq.current) return;
        setItems(Array.isArray(data) ? data : []);
        setState("done");
      } catch {
        if (mine !== seq.current) return;
        setState("error"); setItems([]);
      }
    }, DEBOUNCE_MS);
  }

  function pick(it: DrugSuggestion) {
    cancel();
    onPick(it.name);
    setOpen(false); setState("idle"); setItems([]); setActive(-1);
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (disabled) return;
    if (e.key === "ArrowDown") {
      e.preventDefault();
      if (!open && items.length > 0) setOpen(true);
      if (items.length > 0) setActive((a) => (a + 1) % items.length);
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      if (items.length > 0) setActive((a) => (a <= 0 ? items.length - 1 : a - 1));
    } else if (e.key === "Home" && open && active >= 0) {
      e.preventDefault(); setActive(0);
    } else if (e.key === "End" && open && active >= 0) {
      e.preventDefault(); setActive(items.length - 1);
    } else if (e.key === "Enter" && open && active >= 0 && items[active]) {
      e.preventDefault();
      pick(items[active]);
    } else if (e.key === "Escape" && open) {
      e.preventDefault(); e.stopPropagation(); setOpen(false); setActive(-1);
    }
  }

  const expanded = open && !disabled && state !== "idle";
  const status =
    state === "loading" ? "Searching…"
    : state === "error" ? "Suggestions are unavailable. You can still type the name."
    : state === "done" && items.length === 0 ? "No match in your pharmacy list or past prescriptions. Your own text will be used."
    : state === "done" ? `${items.length} suggestion${items.length === 1 ? "" : "s"}. Use the arrow keys, Enter to choose.`
    : "";

  return (
    <>
      <label className={s.label} htmlFor={id}>{label}</label>
      <input
        id={id}
        className={s.field}
        role="combobox"
        aria-autocomplete="list"
        aria-expanded={expanded}
        aria-controls={listId}
        aria-activedescendant={expanded && active >= 0 ? `${uid}-opt-${active}` : undefined}
        autoComplete="off"
        spellCheck={false}
        placeholder={placeholder}
        value={value}
        disabled={disabled}
        maxLength={200}
        onChange={(e) => { onType(e.target.value); search(e.target.value); }}
        onKeyDown={onKeyDown}
        onBlur={() => { setOpen(false); setActive(-1); }}
        onFocus={() => { if (state === "done" || state === "loading") setOpen(true); }}
      />
      {hint && <p className={s.hint}>{hint}</p>}
      <span className={s.srOnly} role="status" aria-live="polite">{expanded ? status : ""}</span>
      <ul id={listId} role="listbox" aria-label={`${label} suggestions`} className={s.listbox} hidden={!expanded}>
        {expanded && state === "loading" && <li className={s.listNote} role="presentation">Searching…</li>}
        {expanded && state === "error" && <li className={s.listNote} role="presentation">{status}</li>}
        {expanded && state === "done" && items.length === 0 && <li className={s.listNote} role="presentation">{status}</li>}
        {expanded && items.map((it, n) => (
          <li
            key={`${it.source}-${it.name}-${it.code ?? ""}`}
            id={`${uid}-opt-${n}`}
            role="option"
            aria-selected={n === active}
            className={`${s.option} ${n === active ? s.optionActive : ""}`}
            onMouseDown={(e) => { e.preventDefault(); pick(it); }}
            onMouseMove={() => setActive(n)}
          >
            <span>{it.name}</span>
            <span className={s.optionMeta}>
              {it.source === "pharmacy" ? <span>Pharmacy list</span> : <span>Prescribed before</span>}
              {it.source === "pharmacy" && it.inStock === true && <StatusPill tone="ok" label="In stock" />}
              {it.source === "pharmacy" && it.inStock === false && <StatusPill tone="warn" label="Out of stock" />}
              {it.prescriptionOnly === true && <span>Rx only</span>}
            </span>
          </li>
        ))}
      </ul>
    </>
  );
}
