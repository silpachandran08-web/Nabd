"use client";
// FEF-033: client-side validation for the four high-volume forms. The schemas come from the API (generated zod, FEF-014) and are
// narrowed with .pick(); this file only turns zod issues into one message per field, shows them on blur and on submit
// (aria-invalid + aria-describedby via useFormErrors), focuses the first invalid field, and warns before an unsaved form is closed.
import { useCallback, useEffect } from "react";
import type { FormErrors } from "./formErrors";

type Issue = { path: PropertyKey[]; code: string; message: string; origin?: string };
type Schema = { safeParse: (v: unknown) => { success: boolean; error?: { issues: Issue[] } } };

/** Default (English) texts; callers with a catalogue pass their own `translate`. Keys are the only thing schemas emit. */
export const FORM_ERRORS_EN: Record<string, string> = {
  required: "This field is required.",
  invalid: "Check this value.",
  tooLong: "This is too long.",
  phoneInvalid: "Enter a valid mobile number for this clinic's country.",
  dobFuture: "Date of birth cannot be in the future.",
  amountPositive: "Enter an amount above 0.",
  discountReason: "Pick a reason for the discount.",
  discountTooBig: "The discount cannot be more than the subtotal.",
};
export type Translate = (key: string) => string;
const english: Translate = (k) => FORM_ERRORS_EN[k] ?? k;

/** First problem per top-level field, as a message key. Zod's own size/type issues become required / tooLong / invalid. */
export function fieldIssues(schema: Schema, values: unknown): Record<string, string> {
  const r = schema.safeParse(values);
  const out: Record<string, string> = {};
  if (r.success) return out;
  for (const i of r.error?.issues ?? []) {
    const f = String(i.path[0] ?? "");
    if (!f || f in out) continue;
    out[f] = i.code === "custom" ? i.message : i.code === "too_big" ? "tooLong" : i.code === "too_small" && i.origin === "number" ? "amountPositive" : i.code === "too_small" || i.code === "invalid_type" ? "required" : "invalid";
  }
  return out;
}

/** blur(): show or clear the one field's error. submit(): show every error, focus the first invalid control (DOM order) and return false. */
export function useFormValidation(fe: Pick<FormErrors, "setFields">, translate: Translate = english) {
  const { setFields } = fe;
  const blur = useCallback((schema: Schema, name: string, values: unknown) => {
    const key = fieldIssues(schema, values)[name];
    setFields((f) => {
      if (key) return { ...f, [name]: translate(key) };
      if (!(name in f)) return f;
      const { [name]: _gone, ...rest } = f; // eslint-disable-line @typescript-eslint/no-unused-vars
      return rest;
    });
  }, [setFields, translate]);
  const submit = useCallback((schema: Schema, values: unknown, scope: ParentNode | null = null): boolean => {
    const issues = fieldIssues(schema, values);
    const names = Object.keys(issues);
    if (names.length === 0) return true;
    setFields(() => Object.fromEntries(names.map((n) => [n, translate(issues[n])])));
    requestAnimationFrame(() => ((scope ?? document).querySelector('[aria-invalid="true"]') as HTMLElement | null)?.focus());
    return false;
  }, [setFields, translate]);
  return { blur, submit };
}

/** The browser asks before the tab is closed or the page left while `dirty`. */
export function useUnsavedGuard(dirty: boolean) {
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ""; };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);
}
