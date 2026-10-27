"use client";

import { useEffect, useId, useRef, useState, type ReactNode } from "react";

const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

/** True on a coarse-pointer phone-width screen (pure browser query, SSR-safe: false on the server). */
export function useCoarsePhone(): boolean {
  const [on, setOn] = useState(false);
  useEffect(() => {
    const q = window.matchMedia("(pointer: coarse) and (max-width: 768px)");
    const sync = () => setOn(q.matches);
    sync();
    q.addEventListener("change", sync);
    return () => q.removeEventListener("change", sync);
  }, []);
  return on;
}

// Bottom sheet: role=dialog, focus moves in and is restored, Tab is trapped, Escape closes, and the browser Back
// button closes it (a history entry is pushed on open) instead of leaving the page.
export function BottomSheet({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const closeRef = useRef(onClose);
  // Escape and a tap on the scrim go through history.back() so the entry pushed on open is dropped and popstate closes the sheet.
  // shortcut: closing from inside (choosing an option, following a link) leaves one extra history entry; upgrade if that annoys.
  const dismissRef = useRef(() => {});
  useEffect(() => {
    closeRef.current = onClose;
    dismissRef.current = () => { if (history.state?.nbuSheet) history.back(); else closeRef.current(); };
  });
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const node = ref.current;
    (node?.querySelector<HTMLElement>(FOCUSABLE) ?? node)?.focus();
    history.pushState({ nbuSheet: true }, "");
    const onPop = () => closeRef.current();
    window.addEventListener("popstate", onPop);
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") { e.stopPropagation(); dismissRef.current(); return; }
      if (e.key !== "Tab" || !node) return;
      const items = Array.from(node.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (items.length === 0) { e.preventDefault(); return; }
      const first = items[0], last = items[items.length - 1];
      if (e.shiftKey && (document.activeElement === first || document.activeElement === node)) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    }
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      window.removeEventListener("popstate", onPop);
      opener?.focus?.();
    };
  }, []);
  return (
    <div className="nbu-scrim" onClick={() => dismissRef.current()}>
      <div ref={ref} role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1} className="nbu-sheet" onClick={(e) => e.stopPropagation()}>
        <div className="nbu-sheet-grip" aria-hidden="true" />
        <h2 id={titleId} className="nbu-sheet-title">{title}</h2>
        {children}
      </div>
    </div>
  );
}

// One component for menus/selects/popovers: a bottom sheet on a coarse-pointer phone, an anchored popover elsewhere.
export function SheetOrPopover({ open, title, onClose, children }: { open: boolean; title: string; onClose: () => void; children: ReactNode }) {
  const phone = useCoarsePhone();
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open || phone) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    const onDown = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) onClose(); };
    document.addEventListener("keydown", onKey);
    document.addEventListener("mousedown", onDown);
    return () => { document.removeEventListener("keydown", onKey); document.removeEventListener("mousedown", onDown); };
  }, [open, phone, onClose]);
  if (!open) return null;
  return phone ? <BottomSheet title={title} onClose={onClose}>{children}</BottomSheet> : <div ref={ref} className="nbu-popover" role="group" aria-label={title}>{children}</div>;
}

// Select replacement that follows the sheet rule. Options are a listbox of buttons (one tab stop each).
export function SelectMenu<T extends string>({ label, value, options, onChange }: { label: string; value: T; options: { value: T; label: string }[]; onChange: (v: T) => void }) {
  const [open, setOpen] = useState(false);
  const btn = useRef<HTMLButtonElement>(null);
  const cur = options.find((o) => o.value === value);
  return (
    <div className="nbu-menuwrap">
      <button ref={btn} type="button" className="nbu-input nbu-select" aria-haspopup="listbox" aria-expanded={open} onClick={() => setOpen((o) => !o)}>{cur?.label ?? label}</button>
      <SheetOrPopover open={open} title={label} onClose={() => { setOpen(false); btn.current?.focus(); }}>
        <div role="listbox" aria-label={label} className="nbu-options">
          {options.map((o) => (
            <button key={o.value} type="button" role="option" aria-selected={o.value === value} className="nbu-option" onClick={() => { onChange(o.value); setOpen(false); btn.current?.focus(); }}>{o.label}</button>
          ))}
        </div>
      </SheetOrPopover>
    </div>
  );
}
