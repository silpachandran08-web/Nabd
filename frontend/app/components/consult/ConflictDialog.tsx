"use client";

import { useState } from "react";
import Dialog from "../ui/Dialog";
import { NOTE_FIELD_LABEL } from "./consultFlow";
import type { NoteFields } from "./draftStore";
import s from "./consultParts.module.css";

type Choice = "mine" | "server" | "merge";
const FIELDS: (keyof NoteFields)[] = ["subjective", "objective", "assessment", "plan", "diagnosis"];

/** Section-level conflict resolution (DOC-014): the local draft and the server copy both changed.
 * Nothing is applied until the doctor confirms each differing section. */
export default function ConflictDialog({ mine, server, onResolve, onClose }: {
  mine: NoteFields; server: NoteFields; onResolve: (merged: NoteFields) => void; onClose: () => void;
}) {
  const differing = FIELDS.filter((f) => mine[f] !== server[f]);
  const [choice, setChoice] = useState<Partial<Record<keyof NoteFields, Choice>>>({});
  const allChosen = differing.every((f) => choice[f]);

  function apply() {
    const out = { ...server };
    for (const f of differing) {
      const c = choice[f];
      if (c === "mine") out[f] = mine[f];
      else if (c === "merge") out[f] = [server[f], mine[f]].filter((t) => t.trim() !== "").join("\n\n");
    }
    onResolve(out);
  }

  return (
    <Dialog title="Note changed on the server" onClose={onClose} size="lg">
      <p className={s.muted}>Your unsaved local draft and the saved note both changed. Choose what to keep for each section; nothing is overwritten until you confirm.</p>
      <div className={s.stack} style={{ marginBlockStart: "var(--nb-space-12)" }}>
        {differing.map((f) => (
          <fieldset key={f} className={s.card} style={{ margin: 0 }}>
            <legend className={s.cardTitle}>{NOTE_FIELD_LABEL[f]}</legend>
            <p className={s.muted}><strong>Mine (this device):</strong> {mine[f] || "(empty)"}</p>
            <p className={s.muted}><strong>Server:</strong> {server[f] || "(empty)"}</p>
            <div className={s.row}>
              {(["mine", "server", "merge"] as Choice[]).map((c) => (
                <label key={c} className={s.check}>
                  <input type="radio" name={`c-${f}`} checked={choice[f] === c} onChange={() => setChoice((p) => ({ ...p, [f]: c }))} />
                  {c === "mine" ? "Keep mine" : c === "server" ? "Keep server" : "Merge (server, then mine)"}
                </label>
              ))}
            </div>
          </fieldset>
        ))}
      </div>
      <div className={s.dialogActions}>
        <button type="button" className={s.btn} onClick={onClose}>Decide later</button>
        <button type="button" className={s.btnPrimary} onClick={apply} disabled={!allChosen}>Apply choices</button>
      </div>
    </Dialog>
  );
}
