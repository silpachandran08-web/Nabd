"use client";

// FEF-017: one way to show a failed write. A Problem+JSON `errors[]` (field, message) lands next to the input
// (aria-invalid + aria-describedby) AND in a summary with role="alert"; anything else is a plain message in the summary.
import { useCallback, useState } from "react";
import { ApiError, errorText } from "./api";

/** The text for a failed call. A 403 shows the server's own reason when it has one (S7-B02). */
export const failText = (e: unknown, fallback: string) =>
  e instanceof ApiError && e.status === 403 ? e.detail || "Your role isn't allowed to do this." : errorText(e, fallback);

export type FormErrors = {
  message: string | null;
  fields: Record<string, string>;
  /** Record a failure; field errors (400) are kept per field, the rest becomes the summary line. */
  fail: (e: unknown, fallback: string) => void;
  set: (message: string | null) => void;
  clear: () => void;
  clearField: (name: string) => void;
  /** FEF-033: replace the per-field messages (client-side validation). */
  setFields: (update: (f: Record<string, string>) => Record<string, string>) => void;
  /** aria attributes for the input named `name`. */
  attrs: (name: string) => { "aria-invalid"?: true; "aria-describedby"?: string };
  prefix: string;
  labels: Record<string, string>;
};

/** `prefix` keeps element ids unique per form; `labels` maps a server field name to the label the person sees. */
export function useFormErrors(prefix: string, labels: Record<string, string> = {}): FormErrors {
  const [state, setState] = useState<{ message: string | null; fields: Record<string, string> }>({ message: null, fields: {} });
  const fail = useCallback((e: unknown, fallback: string) => {
    const fields = e instanceof ApiError && e.status === 400 ? e.fieldErrors() : {};
    setState({ fields, message: Object.keys(fields).length ? null : failText(e, fallback) });
  }, []);
  const set = useCallback((message: string | null) => setState({ message, fields: {} }), []);
  const clear = useCallback(() => setState({ message: null, fields: {} }), []);
  const clearField = useCallback((name: string) => setState((s) => {
    if (!(name in s.fields)) return s;
    const { [name]: _gone, ...rest } = s.fields; // eslint-disable-line @typescript-eslint/no-unused-vars
    return { ...s, fields: rest };
  }), []);
  const setFields = useCallback((update: (f: Record<string, string>) => Record<string, string>) => setState((s) => ({ ...s, fields: update(s.fields) })), []);
  const attrs = (name: string) => (name in state.fields ? { "aria-invalid": true as const, "aria-describedby": `${prefix}-${name}-error` } : {});
  return { ...state, fail, set, clear, clearField, setFields, attrs, prefix, labels };
}

/** The summary (role="alert"): the message, or one line per field error. Renders nothing when there is no error. */
export function ErrorSummary({ fe, className }: { fe: FormErrors; className?: string }) {
  const names = Object.keys(fe.fields);
  if (!fe.message && names.length === 0) return null;
  return (
    <div role="alert" className={className} data-error-summary={fe.prefix}>
      {fe.message}
      {names.length > 0 && (
        <>
          <span>Check these fields:</span>
          <ul style={{ margin: 0, paddingInlineStart: "var(--nb-space-16)" }}>
            {names.map((n) => <li key={n}>{fe.labels[n] ?? n}: {fe.fields[n]}</li>)}
          </ul>
        </>
      )}
    </div>
  );
}

/** The message under the input `name`; the input's aria-describedby points here. */
export function FieldError({ fe, name, className, text }: { fe: FormErrors; name: string; className?: string; text?: (message: string) => string }) {
  const msg = fe.fields[name];
  return msg ? <div id={`${fe.prefix}-${name}-error`} className={className}>{text ? text(msg) : msg}</div> : null;
}
