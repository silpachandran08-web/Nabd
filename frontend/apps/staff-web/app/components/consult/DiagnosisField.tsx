"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import p from "./consultParts.module.css";
import s from "./noteTemplates.module.css";

// DOC-027. The note still stores ONE diagnosis string. If the doctor types or pastes an ICD-10 code it is stored
// in front of the description as "CODE — description". There is no code list in this app: the code is not looked up,
// completed or verified, only its SHAPE (letter, digit, alphanumeric, optional .1-4 characters) is checked so it can
// be told apart from free text when the note is reopened.
const CODE_SHAPE = /^[A-Za-z][0-9][0-9A-Za-z](\.[0-9A-Za-z]{1,4})?$/;
const SEP = " — ";

export function composeDiagnosis(code: string, description: string): string {
  const c = code.trim().toUpperCase();
  const d = description;
  return c && d.trim() ? `${c}${SEP}${d}` : c ? c : d;
}

export function parseDiagnosis(value: string): { code: string; description: string } {
  if (CODE_SHAPE.test(value.trim())) return { code: value.trim(), description: "" }; // a code with no description yet
  const i = value.indexOf(SEP);
  if (i > 0 && CODE_SHAPE.test(value.slice(0, i))) return { code: value.slice(0, i), description: value.slice(i + SEP.length) };
  return { code: "", description: value };
}

export default function DiagnosisField({ value, onChange, disabled, inputClassName }: {
  value: string; onChange: (v: string) => void; disabled: boolean; inputClassName: string;
}) {
  const t = useTranslations("consult.diagnosisField");
  const g = useTranslations("glossary");
  const parsed = parseDiagnosis(value);
  // Text the doctor typed in the code box that is not (yet) a valid code shape; it is kept visible, not stored.
  const [badCode, setBadCode] = useState<string | null>(null);
  const [codeFocused, setCodeFocused] = useState(false); // the "not shaped like a code" message waits until the doctor leaves the box
  const codeShown = badCode ?? parsed.code;

  function onCode(raw: string) {
    const v = raw.trim();
    if (v === "" || CODE_SHAPE.test(v)) {
      setBadCode(null);
      onChange(composeDiagnosis(v, parsed.description));
    } else {
      setBadCode(raw);
      onChange(composeDiagnosis("", parsed.description));
    }
  }

  return (
    <div className={s.dxWrap}>
      <div className={s.dx}>
        <div className={s.dxCode}>
          <label className={s.fieldLabel} htmlFor="diagnosis-code">
            {t("icd10CodeOptional")}
            <input id="diagnosis-code" className={inputClassName} value={codeShown} disabled={disabled} maxLength={8} autoComplete="off"
              aria-invalid={badCode !== null} aria-describedby="diagnosis-help" onFocus={() => setCodeFocused(true)} onBlur={() => setCodeFocused(false)} onChange={(e) => onCode(e.target.value)} />
          </label>
        </div>
        <div>
          <label className={s.fieldLabel} htmlFor="diagnosis">
            {g("diagnosis.label")}
            <input id="diagnosis" className={inputClassName} value={parsed.description} disabled={disabled}
              onChange={(e) => onChange(composeDiagnosis(parsed.code, e.target.value))} />
          </label>
        </div>
      </div>
      {badCode !== null && !codeFocused && (
        <p className={s.dxError} role="alert">&ldquo;{badCode.trim()}{t("isNotShapedLikeAn")}</p>
      )}
      <p id="diagnosis-help" className={`${p.muted} ${s.dxHelp}`}>
        {t("noIcd10CodeList")}
      </p>
    </div>
  );
}
