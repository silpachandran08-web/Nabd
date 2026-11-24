"use client";

// PHL-043: small pieces shared by the Tests card's two forms.
import { useTranslations } from "next-intl";
import { PRIORITIES, type Priority } from "../../lib/labOrders";
import s from "./consultParts.module.css";
import c from "./labTests.module.css";

/** Opens a PDF answer in a new tab for viewing or printing. */
export async function openPdf(get: () => Promise<Blob>): Promise<boolean> {
  try {
    const url = URL.createObjectURL(await get());
    window.open(url, "_blank", "noopener");
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
    return true;
  } catch { return false; }
}

export function PriorityToggle({ value, onChange }: { value: Priority; onChange: (p: Priority) => void }) {
  const t = useTranslations("consult.labTests");
  return (
    <div className={s.row} role="group" aria-label={t("priority")}>
      {PRIORITIES.map((p) => (
        <button key={p} type="button" className={`${value === p ? s.chipOn : ""} ${s.chip} ${c.target}`} aria-pressed={value === p} onClick={() => onChange(p)}>{t(`priorities.${p}`)}</button>
      ))}
    </div>
  );
}

export function Fasting({ on, hours, onChange }: { on: boolean; hours: string; onChange: (on: boolean, hours: string) => void }) {
  const t = useTranslations("consult.labTests");
  return (
    <div className={s.row}>
      <label className={`${s.check} ${c.target}`}>
        <input type="checkbox" checked={on} onChange={(e) => onChange(e.target.checked, hours)} /> {t("fasting")}
      </label>
      {on && (
        <label className={s.row}>
          <span>{t("fastingHours")}</span>
          <input className={`${s.input} ${c.hours} ${c.target}`} inputMode="numeric" maxLength={2} value={hours} onChange={(e) => onChange(on, e.target.value)} />
        </label>
      )}
    </div>
  );
}
