"use client";

import { useTranslations } from "next-intl";
import { useState, type ReactNode } from "react";
import DrugCombobox from "./DrugCombobox";
import StatusPill from "../ui/StatusPill";
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
  const tx = useTranslations("consult.rxPad");
  const g = useTranslations("glossary");
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
      <div className={s.pad} role="group" aria-label={tx("prescriptionLine", { number: index + 1 })}>
        <div className={`${s.cell} ${s.c6}`}>
          <DrugCombobox
            id={`rx-drug-${index}`} label={tx("genericName")} placeholder={tx("typeTheGenericName")} disabled={locked}
            authedFetch={authedFetch} value={item.drugName}
            hint={tx("suggestionsComeFromTheDrug")}
            onType={(t) => onChange({ drugName: t, drugId: null, genericName: null })}
            onPick={(n, it) => onChange(it.drugId
              ? { drugName: [it.generic ?? it.brand, it.strength, it.form].filter(Boolean).join(" "), brandName: it.brand ?? "", drugId: it.drugId, genericName: it.generic ?? null }
              : { drugName: n, drugId: null, genericName: null })}
          />
          {item.drugName.trim() !== "" && !item.drugId && (
            <p className={s.hint} data-testid="uncoded-badge"><StatusPill tone="neutral" label={tx("uncoded")} /> {" "}{tx("notLinkedToTheDrug")}</p>
          )}
        </div>
        <div className={`${s.cell} ${s.c6}`}>
          <DrugCombobox
            id={id("brand")} label={tx("brandOptional")} placeholder={tx("typeOrPickABrand")} disabled={locked}
            authedFetch={authedFetch} value={item.brandName ?? ""}
            hint={tx("safetyChecksRunOnThe")}
            onType={(t) => onChange({ brandName: t })}
            onPick={(n, it) => onChange(it.drugId
              ? { brandName: it.brand ?? n, drugId: it.drugId, genericName: it.generic ?? null,
                  ...(item.drugName.trim() === "" ? { drugName: [it.generic ?? it.brand, it.strength, it.form].filter(Boolean).join(" ") } : {}) }
              : { brandName: n })}
          />
        </div>

        <div className={`${s.cell} ${s.c4}`}>
          <span className={s.label} id={id("sig-l")}>{tx("sigMorningNoonNight")}</span>
          <div className={s.slots} role="group" aria-labelledby={id("sig-l")}>
            {(["morning", "noon", "night"] as const).map((slot, i) => { const name = g(`rx.slot.${slot}`); return (
              <div key={slot} className={s.slot}>
                <span aria-hidden="true">{name}</span>
                <select className={s.field} aria-label={tx("doseOf", { name })} value={slots[i]} disabled={locked} onChange={(e) => setSlot(i, e.target.value)}>
                  <option value="">-</option>
                  {SIG_STEPS.map((v) => <option key={v} value={v}>{v}</option>)}
                </select>
              </div>
            ); })}
          </div>
          <p className={s.hint}>{item.sigPattern ? tx("pattern", { pattern: item.sigPattern }) : tx("pickAllThreeForExample")}</p>
        </div>
        <div className={`${s.cell} ${s.c4}`}>
          <label className={s.label} htmlFor={id("freq")}>{tx("sigTimingFreeText")}</label>
          <input id={id("freq")} className={s.field} placeholder={tx("eGAfterFoodSos")} value={item.frequency} disabled={locked}
            onChange={(e) => onChange({ frequency: e.target.value })} />
        </div>
        <div className={`${s.cell} ${s.c4}`}>
          <label className={s.label} htmlFor={id("route")}>{g("rx.route")}</label>
          <select id={id("route")} className={s.field} value={item.route ?? ""} disabled={locked}
            onChange={(e) => onChange({ route: e.target.value || null })}>
            <option value="">{tx("notSpecified")}</option>
            {!routeKnown && <option value={item.route ?? ""}>{item.route}</option>}
            {ROUTES.map((r) => <option key={r} value={r}>{g(`rx.routes.${r.toLowerCase()}`)}</option>)}
          </select>
        </div>

        <div className={`${s.cell} ${s.c3}`}>
          <label className={s.label} htmlFor={id("dose")}>{g("rx.dosage")}</label>
          <input id={id("dose")} className={s.field} placeholder={tx("eG500Mg")} value={item.dosage} disabled={locked}
            onChange={(e) => onChange({ dosage: e.target.value })} />
        </div>
        <div className={`${s.cell} ${s.c3}`}>
          <label className={s.label} htmlFor={id("dur")}>{g("rx.duration")}</label>
          <input id={id("dur")} className={s.field} placeholder={tx("eG5Days")} value={item.duration} disabled={locked}
            onChange={(e) => change({ duration: e.target.value })} />
        </div>
        <div className={`${s.cell} ${s.c6}`}>
          <span className={s.label} id={id("qty-l")}>{tx("quantityToDispense")}</span>
          <div className={s.qtyRow} role="group" aria-labelledby={id("qty-l")}>
            <input className={s.field} inputMode="decimal" aria-label={g("rx.quantity")} placeholder={g("rx.quantity")} value={qtyShown} disabled={locked}
              onChange={(e) => onQty(e.target.value)} />
            <input className={s.field} aria-label={tx("quantityUnit")} placeholder={tx("unitTabletsMl")} maxLength={40}
              value={item.quantityUnit ?? ""} disabled={locked} onChange={(e) => onChange({ quantityUnit: e.target.value })} />
          </div>
          {suggestion && item.quantity != null && quantityIsAuto && (
            <p className={s.hint} data-testid={`rx-${index}-qty-note`}>
              {tx("suggestedFromSigDuration")}{" "}{formatQty(suggestion.perDay)} {" "}{tx("perDayDays", { days: suggestion.days })}{" "}{formatQty(suggestion.quantity)}{tx("editItIfNeededAnd")}
            </p>
          )}
          {suggestion && !locked && item.quantity != null && !quantityIsAuto && item.quantity !== suggestion.quantity && (
            <div className={s.suggest}>
              <span>{tx("sigDurationWouldGive")}{" "}{formatQty(suggestion.quantity)}.</span>
              <button type="button" className={s.suggestBtn}
                onClick={() => { setLastAuto(suggestion.quantity); onChange({ quantity: suggestion.quantity }); }}>
                {tx("use")}{" "}{formatQty(suggestion.quantity)}
              </button>
            </div>
          )}
          {item.quantity != null && !item.quantityUnit?.trim() && <p className={s.hint}>{tx("addTheUnitSoThe")}</p>}
        </div>

        <div className={`${s.cell} ${s.c12}`}>
          <label className={s.label} htmlFor={id("ins")}>{g("rx.instructions")}</label>
          <input id={id("ins")} className={s.field} placeholder={tx("instructionsForThePatientOr")} value={item.instructions} disabled={locked}
            onChange={(e) => onChange({ instructions: e.target.value })} />
        </div>
      </div>
      {children}
    </div>
  );
}
