import { useId, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from "react";
import { Icon, type IconName } from "./Icon.tsx";

// FEF-023 form primitives. No data fetching, logical CSS only (ui.css), classes prefixed nbu-.
type Tone = "primary" | "secondary" | "ghost" | "danger";

export function Button({ tone = "secondary", loading, children, ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { tone?: Tone; loading?: boolean }) {
  return (
    <button type="button" {...rest} className={`nbu-btn nbu-btn-${tone} ${rest.className ?? ""}`} disabled={rest.disabled || loading} aria-busy={loading || undefined}>
      {children}
    </button>
  );
}

// An icon-only control must be named: `label` is required and becomes aria-label.
export function IconButton({ icon, label, tone = "ghost", ...rest }: Omit<ButtonHTMLAttributes<HTMLButtonElement>, "aria-label"> & { icon: IconName; label: string; tone?: Tone }) {
  return (
    <button type="button" {...rest} className={`nbu-btn nbu-btn-${tone} nbu-btn-icon ${rest.className ?? ""}`} aria-label={label} title={label}>
      <Icon name={icon} />
    </button>
  );
}

export type FieldControlProps = { id: string; "aria-describedby"?: string; "aria-invalid"?: true; "aria-required"?: true };

// Wires label, hint, error, aria-describedby and aria-invalid for any control.
export function Field({ label, hint, error, required, children }: { label: string; hint?: string; error?: string; required?: boolean; children: (p: FieldControlProps) => ReactNode }) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errId = error ? `${id}-err` : undefined;
  const describedBy = [hintId, errId].filter(Boolean).join(" ") || undefined;
  return (
    <div className="nbu-field" data-invalid={error ? "" : undefined}>
      <label htmlFor={id} className="nbu-label">{label}{required && <span aria-hidden="true"> *</span>}</label>
      {children({ id, "aria-describedby": describedBy, "aria-invalid": error ? true : undefined, "aria-required": required ? true : undefined })}
      {hint && <div id={hintId} className="nbu-hint">{hint}</div>}
      {error && <div id={errId} className="nbu-error" role="alert"><Icon name="alert" size={14} /> {error}</div>}
    </div>
  );
}

export function Input(props: InputHTMLAttributes<HTMLInputElement>) { return <input {...props} className={`nbu-input ${props.className ?? ""}`} />; }
export function Textarea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) { return <textarea {...props} className={`nbu-input nbu-textarea ${props.className ?? ""}`} />; }
export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) { return <select {...props} className={`nbu-input nbu-select ${props.className ?? ""}`} />; }

export function Checkbox({ label, ...rest }: Omit<InputHTMLAttributes<HTMLInputElement>, "type"> & { label: string }) {
  return <label className="nbu-check"><input {...rest} type="checkbox" /> <span>{label}</span></label>;
}

// A switch is a checkbox with role=switch, so keyboard and form behaviour come for free.
export function Switch({ label, ...rest }: Omit<InputHTMLAttributes<HTMLInputElement>, "type" | "role"> & { label: string }) {
  return <label className="nbu-check nbu-switch"><input {...rest} type="checkbox" role="switch" /> <span>{label}</span></label>;
}

export function Segmented<T extends string>({ label, value, options, onChange }: { label: string; value: T; options: { value: T; label: string }[]; onChange: (v: T) => void }) {
  return (
    <div role="radiogroup" aria-label={label} className="nbu-seg">
      {options.map((o) => (
        <button key={o.value} type="button" role="radio" aria-checked={o.value === value} className="nbu-seg-item" onClick={() => onChange(o.value)}>{o.label}</button>
      ))}
    </div>
  );
}

// ARIA tabs with roving tabindex; arrows/Home/End move and select.
export function Tabs({ label, tabs, value, onChange }: { label: string; tabs: { id: string; label: string }[]; value: string; onChange: (id: string) => void }) {
  const base = useId();
  function onKey(e: React.KeyboardEvent, i: number) {
    const n = tabs.length;
    const to = e.key === "ArrowRight" ? (i + 1) % n : e.key === "ArrowLeft" ? (i - 1 + n) % n : e.key === "Home" ? 0 : e.key === "End" ? n - 1 : -1;
    if (to < 0) return;
    e.preventDefault();
    onChange(tabs[to].id);
    document.getElementById(`${base}-${tabs[to].id}`)?.focus();
  }
  return (
    <div role="tablist" aria-label={label} className="nbu-tabs">
      {tabs.map((t, i) => (
        <button key={t.id} id={`${base}-${t.id}`} type="button" role="tab" aria-selected={t.id === value} tabIndex={t.id === value ? 0 : -1} className="nbu-tab" onClick={() => onChange(t.id)} onKeyDown={(e) => onKey(e, i)}>{t.label}</button>
      ))}
    </div>
  );
}
