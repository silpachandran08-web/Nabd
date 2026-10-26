// ACC-031: the reception desk's keyboard map as pure data and one decision function (no React, no imports), so node can test it.
export type DeskAction = "newWalkIn" | "search" | "checkIn" | "changeDoctor" | "confirm" | "help";

type KeyLike = { key: string; ctrlKey: boolean; metaKey: boolean; altKey: boolean; shiftKey: boolean };

/** What a key press means at the desk, or null. `typing`: focus is in a text field, so a bare "?" is just a character. */
export function deskAction(e: KeyLike, typing: boolean): DeskAction | null {
  if (e.key === "Enter") return e.ctrlKey || e.metaKey ? "confirm" : null;
  if (e.key === "?") return !typing && !e.ctrlKey && !e.metaKey && !e.altKey ? "help" : null; // Shift is how most layouts type "?"
  if (e.ctrlKey || e.metaKey || e.altKey || e.shiftKey) return null;
  switch (e.key) {
    case "F2": return "newWalkIn";
    case "F3": return "search";
    case "F4": return "checkIn";
    case "F6": return "changeDoctor";
    default: return null;
  }
}

/** Rows of the shortcut sheet. `needs`: the grants (all of them) that make the action exist for this role. */
export const SHORTCUT_ROWS: { action: DeskAction | "back"; keys: string[]; needs: ("queue:create" | "patients:view" | "patients:create")[] }[] = [
  { action: "newWalkIn", keys: ["F2"], needs: ["queue:create", "patients:view", "patients:create"] },
  { action: "search", keys: ["F3"], needs: ["queue:create", "patients:view"] },
  { action: "checkIn", keys: ["F4"], needs: ["queue:create"] },
  { action: "changeDoctor", keys: ["F6"], needs: [] },
  { action: "confirm", keys: ["Ctrl", "Enter"], needs: [] },
  { action: "back", keys: ["Esc"], needs: [] },
  { action: "help", keys: ["?"], needs: [] },
];
