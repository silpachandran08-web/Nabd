// FEF-034: the one registry of keyboard shortcuts outside the reception desk keys (those stay in components/arrivals/shortcuts, ACC-031).
// Pure: scope (route prefixes), grants and role decide what exists; the '?' overlay, tooltips and aria-keyshortcuts all read this.
import type { RoleContext } from "./navRegistry";

export type Shortcut = {
  id: string;
  keys: string[]; // shown as <kbd> parts
  aria: string; // aria-keyshortcuts value
  label: string;
  single?: boolean; // a bare character: disabled while the user types
  scope: "all" | string[]; // route prefixes
  needs?: string[]; // every grant
  roles?: RoleContext[];
};

/** Screens that mount the desk key layer (F2/F3/F4, its own '?' sheet and Ctrl+Enter). */
export const DESK_SCREENS = ["/arrivals", "/schedule", "/patients"];

export const SHORTCUTS: Shortcut[] = [
  { id: "palette", keys: ["Ctrl", "K"], aria: "Control+K Meta+K", label: "Open the command palette", scope: "all" },
  { id: "search", keys: ["/"], aria: "/", label: "Search patients", single: true, scope: "all", needs: ["patients:view"] },
  { id: "walkin", keys: ["W"], aria: "W", label: "Register a walk-in", single: true, scope: "all", needs: ["queue:create", "patients:view"], roles: ["reception"] },
  { id: "next", keys: ["N"], aria: "N", label: "Call the next patient", single: true, scope: ["/my-day"], roles: ["doctor"] },
  { id: "save", keys: ["Ctrl", "S"], aria: "Control+S Meta+S", label: "Save the note", scope: ["/consult"], needs: ["clinical:edit"] },
  { id: "submit", keys: ["Ctrl", "Enter"], aria: "Control+Enter Meta+Enter", label: "Submit the open dialog", scope: "all" },
  { id: "help", keys: ["?"], aria: "?", label: "Show this list", single: true, scope: "all" },
];

const inScope = (s: Shortcut, path: string) => s.scope === "all" || s.scope.some((p) => path === p || path.startsWith(`${p}/`));

/** The shortcuts that exist on `path` for this session. */
export function shortcutsFor(path: string, perms: readonly string[], role: RoleContext | null): Shortcut[] {
  return SHORTCUTS.filter((s) => inScope(s, path) && (s.needs ?? []).every((p) => perms.includes(p)) && (!s.roles || (!!role && s.roles.includes(role))));
}

/** Tooltip and aria-keyshortcuts for the control that runs shortcut `id`. */
export function keyHint(id: string): { title: string; "aria-keyshortcuts": string } {
  const s = SHORTCUTS.find((x) => x.id === id);
  if (!s) throw new Error(`unknown shortcut ${id}`);
  return { title: `${s.label} (${s.keys.join("+")})`, "aria-keyshortcuts": s.aria };
}

/** True when focus is somewhere the user types: single-key shortcuts must not fire there. */
export function isTyping(el: { tagName?: string; isContentEditable?: boolean } | null): boolean {
  const t = el?.tagName;
  return t === "INPUT" || t === "TEXTAREA" || t === "SELECT" || !!el?.isContentEditable;
}

/** Fuzzy match for the palette: every query word is a substring of the text, or (3+ letters) its letters appear in order inside one word of it ("wlk" finds "Walk-in"). */
export function fuzzyMatch(query: string, text: string): boolean {
  const t = text.toLowerCase();
  const words = t.split(/[^\p{L}\p{N}]+/u).filter(Boolean);
  return query.toLowerCase().split(/\s+/).filter(Boolean).every((w) => {
    if (t.includes(w)) return true;
    return w.length >= 3 && words.some((x) => { let i = 0; for (const c of x) if (c === w[i]) i++; return i === w.length; });
  });
}
