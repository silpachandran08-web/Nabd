"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { Icon, type IconName } from "./Icon.tsx";

export type PillTone = "ok" | "warn" | "danger" | "info" | "sync" | "urgent" | "neutral";
const PILL_ICON: Record<PillTone, IconName> = { ok: "check", warn: "clock", danger: "alert", info: "info", sync: "sync", urgent: "alert", neutral: "list" };

// A status is never colour alone: icon (shape) + text, always.
export function StatusPill({ tone = "neutral", label, icon }: { tone?: PillTone; label: string; icon?: IconName }) {
  return <span className={`nbu-pill nbu-pill-${tone}`}><Icon name={icon ?? PILL_ICON[tone]} size={12} />{label}</span>;
}
export function Badge({ count, label }: { count: number; label: string }) {
  return count > 0 ? <span className="nbu-badge" aria-label={`${count} ${label}`}>{count > 99 ? "99+" : count}</span> : null;
}
export function Kpi({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return <div className="nbu-kpi"><div className="nbu-kpi-label">{label}</div><div className="nbu-kpi-value">{value}</div>{hint && <div className="nbu-hint">{hint}</div>}</div>;
}
export function Avatar({ name }: { name: string }) {
  const letters = name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => [...p][0]).join("").toUpperCase();
  return <span className="nbu-avatar" role="img" aria-label={name}>{letters}</span>;
}
export function Skeleton({ rows = 4, label = "Loading" }: { rows?: number; label?: string }) {
  return <div className="nbu-skel" role="status" aria-busy="true" aria-label={label}>{Array.from({ length: rows }, (_, i) => <div key={i} className="nbu-skel-row" />)}</div>;
}
export function EmptyState({ title, hint, icon = "inbox", action }: { title: string; hint?: string; icon?: IconName; action?: ReactNode }) {
  return <div className="nbu-state"><Icon name={icon} size={28} /><div className="nbu-state-title">{title}</div>{hint && <div className="nbu-hint">{hint}</div>}{action}</div>;
}
export function ErrorState({ message, onRetry, retryLabel = "Retry" }: { message: string; onRetry?: () => void; retryLabel?: string }) {
  return (
    <div className="nbu-state nbu-state-error" role="alert">
      <Icon name="alert" size={28} /><div className="nbu-state-title">{message}</div>
      {onRetry && <button type="button" className="nbu-btn nbu-btn-secondary" onClick={onRetry}><Icon name="refresh" size={14} /> {retryLabel}</button>}
    </div>
  );
}

// Toast queue: two live regions (polite for info/ok, assertive for errors) so a screen reader announces without moving focus.
export type ToastInput = { message: string; tone?: "ok" | "info" | "danger"; ms?: number };
type ToastItem = ToastInput & { id: number };
const ToastCtx = createContext<(t: ToastInput) => void>(() => {});
export const useToast = () => useContext(ToastCtx);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const seq = useRef(0);
  const timers = useRef(new Map<number, ReturnType<typeof setTimeout>>());
  const dismiss = useCallback((id: number) => {
    clearTimeout(timers.current.get(id));
    timers.current.delete(id);
    setItems((l) => l.filter((x) => x.id !== id));
  }, []);
  const push = useCallback((t: ToastInput) => {
    const id = ++seq.current;
    setItems((l) => [...l.slice(-4), { ...t, id }]);
    // Errors stay until dismissed (a clinician may be looking away); others fade.
    if (t.tone !== "danger") timers.current.set(id, setTimeout(() => dismiss(id), t.ms ?? 5000));
  }, [dismiss]);
  useEffect(() => { const m = timers.current; return () => m.forEach(clearTimeout); }, []);
  const region = (assertive: boolean) => (
    <div className="nbu-toasts" role={assertive ? "alert" : "status"} aria-live={assertive ? "assertive" : "polite"} data-region={assertive ? "assertive" : "polite"}>
      {items.filter((x) => (x.tone === "danger") === assertive).map((x) => (
        <div key={x.id} className={`nbu-toast nbu-toast-${x.tone ?? "info"}`}>
          <Icon name={x.tone === "danger" ? "alert" : x.tone === "ok" ? "check" : "info"} size={16} />
          <span>{x.message}</span>
          <button type="button" className="nbu-btn nbu-btn-ghost nbu-btn-icon" aria-label="Dismiss" onClick={() => dismiss(x.id)}><Icon name="x" size={14} /></button>
        </div>
      ))}
    </div>
  );
  return <ToastCtx.Provider value={push}>{children}{region(false)}{region(true)}</ToastCtx.Provider>;
}
