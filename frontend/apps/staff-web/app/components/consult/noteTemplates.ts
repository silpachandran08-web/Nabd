// DOC-027: doctor-authored note templates. Matches /v1/clinical/note-templates (NoteTemplateController).
// Nothing here is clinical content: templates hold only what the signed-in doctor typed and saved.
import type { NoteFields } from "./draftStore";

export type NoteTemplate = {
  id: string; name: string; subjective: string; objective: string; assessment: string; plan: string;
  tags: string[]; favourite: boolean; createdAt: string; updatedAt: string;
};

export const SECTION_KEYS = ["subjective", "objective", "assessment", "plan"] as const;
export type SectionKey = (typeof SECTION_KEYS)[number];
export type Sections = Record<SectionKey, string>;

export const MAX_TAGS = 10;
export const MAX_TAG_LEN = 30;
export const MAX_NAME_LEN = 80;

/** Adds the template's text to the note. Empty target -> the template text; existing text is kept and the template is appended after a blank line, so applying never discards what the doctor wrote. */
export function mergeSections(note: NoteFields, tpl: Sections): Sections {
  const out = {} as Sections;
  for (const k of SECTION_KEYS) {
    const add = tpl[k] ?? "";
    const cur = note[k] ?? "";
    out[k] = add.trim() === "" ? cur : cur.trim() === "" ? add : `${cur.replace(/\s+$/, "")}\n\n${add}`;
  }
  return out;
}

export const hasSectionText = (s: Sections) => SECTION_KEYS.some((k) => (s[k] ?? "").trim() !== "");

/** Tags exactly as the server normalises them: trimmed, blanks dropped, case-insensitive duplicates removed. */
export function addTag(tags: string[], raw: string): string[] {
  const v = raw.trim();
  if (!v || v.length > MAX_TAG_LEN || tags.length >= MAX_TAGS) return tags;
  if (tags.some((t) => t.toLowerCase() === v.toLowerCase())) return tags;
  return [...tags, v];
}

export function sortTemplates(list: NoteTemplate[]): NoteTemplate[] {
  return [...list].sort((a, b) => Number(b.favourite) - Number(a.favourite) || a.name.localeCompare(b.name));
}
