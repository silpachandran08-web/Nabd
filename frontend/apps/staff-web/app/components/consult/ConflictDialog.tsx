"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import Dialog from "../ui/Dialog";
import { NOTE_FIELD_LABEL } from "@nabd/domain";
import type { NoteFields } from "./draftStore";
import s from "./consultParts.module.css";

type Choice = "mine" | "server" | "merge";
const FIELDS: (keyof NoteFields)[] = ["subjective", "objective", "assessment", "plan", "diagnosis"];

/** Section-level conflict resolution (DOC-014): the local draft and the server copy both changed.
 * Nothing is applied until the doctor confirms each differing section. */
export default function ConflictDialog({ mine, server, onResolve, onClose }: {
  mine: NoteFields; server: NoteFields; onResolve: (merged: NoteFields) => void; onClose: () => void;
}) {
  const tx = useTranslations("consult.conflictDialog");
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
    <Dialog title={tx("noteChangedOnTheServer")} onClose={onClose} size="lg">
      <p className={s.muted}>{tx("yourUnsavedLocalDraftAnd")}</p>
      <div className={`${s.stack} ${s.mtM}`}>
        {differing.map((f) => (
          <fieldset key={f} className={`${s.card} ${s.m0}`}>
            <legend className={s.cardTitle}>{NOTE_FIELD_LABEL[f]}</legend>
            <p className={s.muted}><strong>{tx("mineThisDevice")}</strong> {mine[f] || tx("empty")}</p>
            <p className={s.muted}><strong>{tx("server")}</strong> {server[f] || tx("empty")}</p>
            <div className={s.row}>
              {(["mine", "server", "merge"] as Choice[]).map((c) => (
                <label key={c} className={s.check}>
                  <input type="radio" name={`c-${f}`} checked={choice[f] === c} onChange={() => setChoice((p) => ({ ...p, [f]: c }))} />
                  {c === "mine" ? tx("keepMine") : c === "server" ? tx("keepServer") : tx("mergeServerThenMine")}
                </label>
              ))}
            </div>
          </fieldset>
        ))}
      </div>
      <div className={s.dialogActions}>
        <button type="button" className={s.btn} onClick={onClose}>{tx("decideLater")}</button>
        <button type="button" className={s.btnPrimary} onClick={apply} disabled={!allChosen}>{tx("applyChoices")}</button>
      </div>
    </Dialog>
  );
}
