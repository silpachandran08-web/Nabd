"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { api, reasonText } from "../../lib/api";
import { presetCode, type DentalPreset, type Numbering } from "../../lib/dental";
import s from "./consultParts.module.css";

export type DentalSettings = { numbering: Numbering; clinicPresets: DentalPreset[]; myPresets: DentalPreset[]; presets: DentalPreset[] };
type Row = { code: string | null; label: string; la: boolean; drugQuery: string; replaces: string };

const MAX = 24;

/** CLN-050: the clinic's chair-side phrases (setup:edit) or the dentist's own ones, which replace a clinic phrase with the same code
 * or add new ones. Inline in the dental drawer; the server validates the list again. */
export default function DentalPresetEditor({ scope, settings, onSaved, onClose }: {
  scope: "mine" | "clinic"; settings: DentalSettings; onSaved: (s: DentalSettings) => void; onClose: () => void;
}) {
  const tx = useTranslations("consult.dentalDrawer");
  const clinicCodes = new Set(settings.clinicPresets.map((p) => p.code));
  const [rows, setRows] = useState<Row[]>(() => (scope === "clinic" ? settings.clinicPresets : settings.myPresets).map((p) => ({
    code: p.code, label: p.label, la: p.la, drugQuery: p.drugQuery ?? "", replaces: scope === "mine" && clinicCodes.has(p.code) ? p.code : "",
  })));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const edit = (i: number, patch: Partial<Row>) => setRows((r) => r.map((x, j) => (j === i ? { ...x, ...patch } : x)));

  async function save() {
    if (rows.some((r) => !r.label.trim())) { setError(tx("phraseNeedsLabel")); return; }
    const used = new Set<string>();
    const presets: DentalPreset[] = rows.map((r) => {
      let code = r.replaces || (r.code && !(scope === "mine" && clinicCodes.has(r.code)) ? r.code : presetCode(r.label));
      for (let n = 2; used.has(code) && !r.replaces; n++) code = `${presetCode(r.label).slice(0, 36)}_${n}`;
      used.add(code);
      return { code, label: r.label.trim(), la: r.la, drugQuery: r.la && r.drugQuery.trim() ? r.drugQuery.trim() : null };
    });
    setSaving(true);
    setError(null);
    try {
      onSaved(scope === "clinic"
        ? await api.put<DentalSettings>("/specialty/dental/settings", { numbering: settings.numbering, presets })
        : await api.put<DentalSettings>("/specialty/dental/settings/my-presets", { presets }));
    } catch (e) { setError(reasonText(e, tx("phrasesNotSaved"))); } finally { setSaving(false); }
  }

  return (
    <fieldset className={s.fieldset}>
      <legend className={s.legend}>{scope === "clinic" ? tx("editClinicPhrases") : tx("editMyPhrases")}</legend>
      <p className={s.muted}>{scope === "clinic" ? tx("clinicPhrasesHint") : tx("myPhrasesHint")}</p>
      {error && <div className={`${s.notice} ${s.noticeDanger}`} role="alert">{error}</div>}
      {rows.length === 0 && <p className={s.muted}>{tx("noPhrases")}</p>}
      {rows.map((r, i) => (
        <div key={i} className={s.formGrid}>
          <label className={s.field}>{tx("phraseLabel")}
            <input className={s.input} value={r.label} maxLength={60} onChange={(e) => edit(i, { label: e.target.value })} />
          </label>
          {scope === "mine" && (
            <label className={s.field}>{tx("replaces")}
              <select className={s.input} value={r.replaces} onChange={(e) => edit(i, { replaces: e.target.value })}>
                <option value="">{tx("newPhrase")}</option>
                {settings.clinicPresets.map((p) => <option key={p.code} value={p.code}>{p.label}</option>)}
              </select>
            </label>
          )}
          <label className={s.check}><input type="checkbox" checked={r.la} onChange={(e) => edit(i, { la: e.target.checked })} />{tx("recordsAnaesthetic")}</label>
          {r.la && (
            <label className={s.field}>{tx("pharmacyName")}
              <input className={s.input} value={r.drugQuery} maxLength={60} onChange={(e) => edit(i, { drugQuery: e.target.value })} />
            </label>
          )}
          <button type="button" className={`${s.linkBtn} ${s.linkBtnTouch}`} onClick={() => setRows((x) => x.filter((_, j) => j !== i))}>{tx("remove")}</button>
        </div>
      ))}
      <div className={s.dialogActions}>
        {rows.length < MAX && <button type="button" className={s.btn} onClick={() => setRows((x) => [...x, { code: null, label: "", la: false, drugQuery: "", replaces: "" }])}>{tx("addPhrase")}</button>}
        <button type="button" className={s.btn} onClick={onClose}>{tx("cancel")}</button>
        <button type="button" className={s.btnPrimary} disabled={saving} onClick={() => void save()}>{tx("savePhrases")}</button>
      </div>
    </fieldset>
  );
}
