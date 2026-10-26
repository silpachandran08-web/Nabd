// FEF-030: pure helpers of the note editor (no React), so the autosave rules can be tested without a browser.
import type { NoteFields } from "../draftStore";
import type { Note } from "../visit/types";

export const AUTOSAVE_MS = 10_000;
export const LOCAL_DRAFT_MS = 800;
export const BLANK_DRAFT: NoteFields = { subjective: "", objective: "", assessment: "", plan: "", diagnosis: "" };

export function toFields(n: Note): NoteFields {
  return { subjective: n.subjective ?? "", objective: n.objective ?? "", assessment: n.assessment ?? "", plan: n.plan ?? "", diagnosis: n.diagnosis ?? "" };
}
export const sameFields = (a: NoteFields, b: NoteFields) => (Object.keys(a) as (keyof NoteFields)[]).every((k) => a[k] === b[k]);
