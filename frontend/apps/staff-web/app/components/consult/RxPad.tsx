"use client";

import { useState, type ReactNode } from "react";
import DrugCombobox from "./DrugCombobox";
import { formatQty, formatSig, parseSig, ROUTES, SIG_STEPS, suggestQuantity, type SigSlots } from "@nabd/domain";
import type { PrescriptionItem } from "./shared";
import s from "./RxPad.module.css";

type AuthedFetch = (path: string, init?: RequestInit) => Promise<Response | null>;

/** One prescription line (DOC-033): generic name first, brand second, structured sig (morning-noon-night), route,
 * duration, quantity, instructions. It only edits the line; the server still runs every safety check on the
 * generic AND brand text and gates signing. Nothing here clears, hides or pre-empts a warning, and no unit or
 * quantity is ever changed behind the doctor's back: the suggested quantity is filled only while the doctor has
 * not typed one, it is labelled as a suggestion, and it stays editable. */
export default function RxPad({
  item, index, locked, authedFetch, onChange, children,
}: {
  item: PrescriptionItem; index: number; locked: boolean; authedFetch: AuthedFetch;
  onChange: (patch: Partial<PrescriptionItem>) => void;
  /** The remove button, owned by the page. */
  children?: ReactNode;
}) {
  const [partial, setPartial] = useState<[string, string, string]>(["", "", ""]);
  const [lastAuto, setLastAuto] = useState<number | null>(null);
  const [qtyText, setQtyText] = useState("");

  const pattern = parseSig(item.sigPattern);
  const slots: [string, string, string] = pattern ?? partial;
  const quantityIsAuto = item.quantity == null || item.quantity === lastAuto;
  const suggestion = suggestQuantity(item.sigPattern, item.duration);
  const routeKnown = !item.route || (ROUTES as readonly string[]).includes(item.route);

  /** Applies a change plus, when the quantity is still the auto-suggested one (or empty), the recomputed suggestion. */
  function change(patch: Partial<PrescriptionItem>) {
    const next = { ...item, ...patch };
    if (("sigPattern" in patch || "duration" in patch) && quantityIsAuto) {
      const sug = suggestQuantity(next.sigPattern, next.duration);
      patch = { ...patch, quantity: sug ? sug.quantity : null };
      setLastAuto(sug ? sug.quantity : null);
    }
    onChange(patch);
  }

  function setSlot(i: number, v: string) {
    const nextSlots: [string, string, string] = [slots[0], slots[1], slots[2]];
    nextSlots[i] = v;
    if (nextSlots.every((x) => x !== "")) {
      const np = formatSig(nextSlots as SigSlots);
      // Older readers show `frequency`: mirror the pattern into it only while it is empty or still the previous pattern.
      const mirror = !item.frequency?.trim() || item.frequency === item.sigPattern;
      change({ sigPattern: np, ...(mirror ? { frequency: np } : {}) });
    } else {
      setPartial(nextSlots);
      if (item.sigPattern) {
        const mirror = item.frequency === item.sigPattern;
        change({ sigPattern: null, ...(mirror ? { frequency: "" } : {}) });
      }
    }
  }

  function onQty(text: string) {
    if (!/^\d{0,6}(\.\d{0,2})?$/.test(text)) return;
    setQtyText(text);
    const n = text === "" ? null : Number(text);
    onChange({ quantity: n !== null && n > 0 ? n : null });
  }
  const qtyShown = qtyText !== "" && Number(qtyText) === item.quantity ? qtyText : item.quantity == null ? "" : formatQty(item.quantity);

  const id = (k: string) => `rx-${index}-${k}`;

  return (
    <div className={s.lineRow}>
      <div className={s.pad} role="group" aria-label={`Prescription line ${index + 1}`}>
        <div className={`${s.cell} ${s.c6}`}>
          <DrugCombobox
            id={`rx-drug-${index}`} label="Generic name" placeholder="Type the generic name" disabled={locked}
            authedFetch={authedFetch} value={item.drugName}
            hint="Suggestions come from your pharmacy list and past prescriptions. Any text you type is kept as is."
            onType={(t) => onChange({ drugName: t })} onPick={(n) => onChange({ drugName: n })}
          />
        </div>
        <div className={`${s.cell} ${s.c6}`}>
          <DrugCombobox
            id={id("brand")} label="Brand (optional)" placeholder="Type or pick a brand" disabled={locked}
            authedFetch={authedFetch} value={item.brandName ?? ""}
            hint="Safety checks run on the generic name and the brand."
            onType={(t) => onChange({ brandName: t })} onPick={(n) => onChange({ brandName: n })}
          />
        </div>

        <div className={`${s.cell} ${s.c4}`}>
          <span className={s.label} id={id("sig-l")}>Sig: morning, noon, night</span>
          <div className={s.slots} role="group" aria-labelledby={id("sig-l")}>
            {(["Morning", "Noon", "Night"] as const).map((name, i) => (
              <div key={name} className={s.slot}>
                <span aria-hidden="true">{name}</span>
                <select className={s.field} aria-label={`${name} dose`} value={slots[i]} disabled={locked} onChange={(e) => setSlot(i, e.target.value)}>
                  <option value="">-</option>
                  {SIG_STEPS.map((v) => <option key={v} value={v}>{v}</option>)}
                </select>
              </div>
            ))}
          </div>
          <p className={s.hint}>{item.sigPattern ? `Pattern ${item.sigPattern} (units per dose).` : "Pick all three, for example 1-0-1."}</p>
        </div>
        <div className={`${s.cell} ${s.c4}`}>
          <label className={s.label} htmlFor={id("freq")}>Sig / timing (free text)</label>
          <input id={id("freq")} className={s.field} placeholder="e.g. after food, SOS" value={item.frequency} disabled={locked}
            onChange={(e) => onChange({ frequency: e.target.value })} />
        </div>
        <div className={`${s.cell} ${s.c4}`}>
          <label className={s.label} htmlFor={id("route")}>Route</label>
          <select id={id("route")} className={s.field} value={item.route ?? ""} disabled={locked}
            onChange={(e) => onChange({ route: e.target.value || null })}>
            <option value="">Not specified</option>
            {!routeKnown && <option value={item.route ?? ""}>{item.route}</option>}
            {ROUTES.map((r) => <option key={r} value={r}>{r}</option>)}
          </select>
        </div>

        <div className={`${s.cell} ${s.c3}`}>
          <label className={s.label} htmlFor={id("dose")}>Dosage</label>
          <input id={id("dose")} className={s.field} placeholder="e.g. 500 mg" value={item.dosage} disabled={locked}
            onChange={(e) => onChange({ dosage: e.target.value })} />
        </div>
        <div className={`${s.cell} ${s.c3}`}>
          <label className={s.label} htmlFor={id("dur")}>Duration</label>
          <input id={id("dur")} className={s.field} placeholder="e.g. 5 days" value={item.duration} disabled={locked}
            onChange={(e) => change({ duration: e.target.value })} />
        </div>
        <div className={`${s.cell} ${s.c6}`}>
          <span className={s.label} id={id("qty-l")}>Quantity to dispense</span>
          <div className={s.qtyRow} role="group" aria-labelledby={id("qty-l")}>
            <input className={s.field} inputMode="decimal" aria-label="Quantity" placeholder="Quantity" value={qtyShown} disabled={locked}
              onChange={(e) => onQty(e.target.value)} />
            <input className={s.field} aria-label="Quantity unit" placeholder="Unit (tablets, ml...)" maxLength={40}
              value={item.quantityUnit ?? ""} disabled={locked} onChange={(e) => onChange({ quantityUnit: e.target.value })} />
          </div>
          {suggestion && item.quantity != null && quantityIsAuto && (
            <p className={s.hint} data-testid={`rx-${index}-qty-note`}>
              Suggested from sig × duration: {formatQty(suggestion.perDay)} per day × {suggestion.days} days = {formatQty(suggestion.quantity)}. Edit it if needed and check the unit.
            </p>
          )}
          {suggestion && !locked && item.quantity != null && !quantityIsAuto && item.quantity !== suggestion.quantity && (
            <div className={s.suggest}>
              <span>Sig × duration would give {formatQty(suggestion.quantity)}.</span>
              <button type="button" className={s.suggestBtn}
                onClick={() => { setLastAuto(suggestion.quantity); onChange({ quantity: suggestion.quantity }); }}>
                Use {formatQty(suggestion.quantity)}
              </button>
            </div>
          )}
          {item.quantity != null && !item.quantityUnit?.trim() && <p className={s.hint}>Add the unit so the quantity is unambiguous.</p>}
        </div>

        <div className={`${s.cell} ${s.c12}`}>
          <label className={s.label} htmlFor={id("ins")}>Instructions</label>
          <input id={id("ins")} className={s.field} placeholder="Instructions for the patient or pharmacist" value={item.instructions} disabled={locked}
            onChange={(e) => onChange({ instructions: e.target.value })} />
        </div>
      </div>
      {children}
    </div>
  );
}
