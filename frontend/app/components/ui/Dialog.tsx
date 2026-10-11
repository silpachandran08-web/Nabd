"use client";

import { useEffect, useId, useRef, type ReactNode } from "react";
import styles from "./ui.module.css";

// Open dialogs, innermost last. Only the top one answers Escape and Tab, so a confirm opened from a
// drawer closes alone instead of taking the drawer with it.
const openStack: symbol[] = [];

// Accessible modal: role=dialog, aria-modal, labelled by its title, focus moved in on open and
// restored on close, Escape closes, Tab is trapped. `size="drawer"` renders the side-drawer
// variant (DESIGN.md §8.10); narrow screens turn either into a bottom sheet via CSS.
export default function Dialog({
  title,
  onClose,
  children,
  size = "md",
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  size?: "md" | "lg" | "drawer";
}) {
  const ref = useRef<HTMLDivElement>(null);
  const titleId = useId();
  // Callers pass inline arrows, so onClose changes identity on every parent render. Read it through a
  // ref so the effect below runs once per open: re-running it on each keystroke or autosave tick
  // would restore focus to the opener and then yank it to the Close button mid-typing.
  const closeRef = useRef(onClose);
  useEffect(() => { closeRef.current = onClose; });

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const node = ref.current;
    const focusable = () =>
      Array.from(node?.querySelectorAll<HTMLElement>('a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])') ?? []);
    const me = Symbol("dialog");
    openStack.push(me);
    // A child that already took focus (autoFocus) keeps it; otherwise start at the first control.
    if (!node?.contains(document.activeElement)) (focusable()[0] ?? node)?.focus();
    function onKey(e: KeyboardEvent) {
      if (openStack[openStack.length - 1] !== me) return;
      if (e.key === "Escape") {
        e.stopPropagation();
        closeRef.current();
      } else if (e.key === "Tab") {
        const items = focusable();
        if (items.length === 0) return;
        const first = items[0];
        const last = items[items.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    }
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      openStack.splice(openStack.indexOf(me), 1);
      previous?.focus?.();
    };
  }, []);

  return (
    <div className={styles.scrim} onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={`${styles.dialog} ${styles[`dialog_${size}`]}`}
      >
        <div className={styles.dialogHead}>
          <h2 id={titleId} className={styles.dialogTitle}>{title}</h2>
          <button type="button" className={styles.dialogClose} onClick={onClose} aria-label="Close">×</button>
        </div>
        <div className={styles.dialogBody}>{children}</div>
      </div>
    </div>
  );
}
