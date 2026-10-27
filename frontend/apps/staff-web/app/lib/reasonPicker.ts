// ACC-007: pure rules of the shared reason picker (components/ui/ReasonPicker.tsx).
export type ReasonItem = { code: string; label: string; requiresNote: boolean };

/** Submit gate shared by the dialogs: a reason is chosen and, when it needs a note, the note is not blank. */
export const reasonReady = (item: ReasonItem | null, note: string, askNote = true): boolean =>
  !!item && (!askNote || !item.requiresNote || note.trim().length > 0);
