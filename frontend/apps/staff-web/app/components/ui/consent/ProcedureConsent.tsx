"use client";

import { useTranslations } from "next-intl";
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import type { PatientDetailResponse } from "@nabd/api-client/generated";
import { api, ApiError, intentKeys } from "../../../lib/api";
import { selfStaffId } from "../../../lib/doctorQueries";
import { can } from "../../../lib/permissions";
import Dialog from "../Dialog";
import Icon from "../Icon";
import { ErrorState, Skeleton } from "../StateViews";
import SignaturePad from "./SignaturePad";
import { pickLang, plainText, signable, strokesToSvg, type ConsentTemplate, type Point } from "./consentModel";
import c from "./procedureConsent.module.css";

export type ConsentOrder = { id: string; patientId?: string; patientName?: string; chargeCode?: string; chargeName?: string; patientLanguage?: string | null };

const LANG_NAME: Record<string, string> = { en: "English", hi: "हिन्दी", kn: "ಕನ್ನಡ", te: "తెలుగు", ar: "العربية" };

// CLN-014 (replaces NB-119's typed name): the patient or guardian reads the clinic's consent form in their language, scrolls
// to the end, and signs with a finger; the signed-in staff member is the witness. POST /nursing/procedure-orders/{id}/consent
// (nursing:edit) renders the PDF and stores its SHA-256. For a procedure the clinic has no form for, the typed-name record of
// NB-119 is still offered (the server refuses it once a form exists). No consent wording is written here: it is clinic data.
export default function ProcedureConsent({ order, onClose, onRecorded }: { order: ConsentOrder; onClose: () => void; onRecorded: () => void }) {
  const tx = useTranslations("consult.procedureConsent");
  const keys = useRef(intentKeys());
  const formRef = useRef<HTMLDivElement>(null);
  const [loaded, setLoaded] = useState<{ items: ConsentTemplate[]; patient: PatientDetailResponse | null } | "error" | null>(null);
  const [lang, setLang] = useState<string | null>(null);
  const [readToEnd, setReadToEnd] = useState(false);
  const [signer, setSigner] = useState<{ kind: "patient" | "guardian"; name: string }>({ kind: "patient", name: "" });
  const [strokes, setStrokes] = useState<Point[][]>([]);
  const [assent, setAssent] = useState<Point[][]>([]);
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoaded((cur) => (cur === "error" ? null : cur));
    try {
      const [list, p] = await Promise.all([
        api.get<{ items: ConsentTemplate[] }>(`/nursing/consent-templates?chargeCode=${encodeURIComponent(order.chargeCode ?? "")}`),
        can("patients:view") && order.patientId ? api.get<PatientDetailResponse>(`/patients/${order.patientId}`).catch(() => null) : Promise.resolve(null),
      ]);
      setLoaded({ items: list.items, patient: p });
      setLang((cur) => (cur && signable(list.items).some((t) => t.lang === cur) ? cur : pickLang(list.items, order.patientLanguage)));
      const minor = !!p?.isMinor;
      setSigner((cur) => ({ kind: minor ? "guardian" : "patient", name: cur.name || (minor ? p?.guardianName ?? "" : p?.name ?? order.patientName ?? "") }));
    } catch {
      setLoaded("error");
    }
  }, [order.chargeCode, order.patientId, order.patientLanguage, order.patientName]);

  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(() => { void load(); }, [load]);

  const items = loaded && loaded !== "error" ? loaded.items : null;
  const patient = loaded && loaded !== "error" ? loaded.patient : null;
  const loadError = loaded === "error";
  const { kind, name } = signer;
  const setName = (n: string) => setSigner((cur) => ({ ...cur, name: n }));
  const template = signable(items ?? []).find((t) => t.lang === lang) ?? null;
  const minor = !!patient?.isMinor;

  // A form short enough to fit counts as read; otherwise the reader must reach the end.
  useLayoutEffect(() => {
    const el = formRef.current;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setReadToEnd(!!el && el.scrollHeight <= el.clientHeight + 4);
  }, [template?.id]);

  async function submit() {
    if (!template) return;
    const body = {
      templateId: template.id, templateVersion: template.version, lang: template.lang,
      signer: { kind, name: name.trim(), ...(kind === "guardian" && patient?.guardianId ? { guardianId: patient.guardianId } : {}) },
      signatureSvg: strokesToSvg(strokes), ...(minor && assent.length ? { assentSignatureSvg: strokesToSvg(assent) } : {}),
      witnessStaffId: selfStaffId(),
    };
    await post(body);
  }

  async function submitTyped() {
    if (!name.trim() || !confirmed) return;
    await post({ signedName: name.trim() });
  }

  async function post(body: Record<string, unknown>) {
    setBusy(true);
    setError(null);
    try {
      await api.post(`/nursing/procedure-orders/${order.id}/consent`, body, { idempotencyKey: keys.current.get("consent", body) });
      keys.current.done("consent");
      onRecorded();
    } catch (e) {
      // Stay open: consent that failed to record must not look recorded.
      const type = e instanceof ApiError ? String(e.problem?.type ?? "") : "";
      if (type.endsWith("TEMPLATE_VERSION_STALE")) { setError(tx("formChanged")); setReadToEnd(false); void load(); }
      else setError(e instanceof ApiError && e.detail ? e.detail : tx("consentWasNotRecordedTry"));
    } finally {
      setBusy(false);
    }
  }

  const guardianMissing = minor && !patient?.guardianId;
  const canSign = !!template && readToEnd && name.trim().length > 0 && strokes.length > 0 && !guardianMissing && (kind === "patient" || !!patient?.guardianId);

  return (
    <Dialog title={tx("recordProcedureConsent")} onClose={onClose} size="lg">
      <div className={c.stack}>
        <p className={c.muted}>{order.patientName} · {order.chargeName}</p>
        <div className={`${c.notice} ${c.noticeDanger}`}>
          <Icon name="alert" size={16} />
          <span>{tx("hardGateThisProcedureCannot")}</span>
        </div>
        {items === null && !loadError && <Skeleton rows={4} label={tx("loadingForm")} />}
        {loadError && <ErrorState message={tx("couldNotLoadForm")} onRetry={() => void load()} retryLabel={tx("retry")} />}

        {items !== null && !template && (
          <>
            <div className={`${c.notice} ${c.noticeWarn}`} role="status">
              <Icon name="alert" size={16} />
              <span>{tx("noClinicForm")}</span>
            </div>
            <div className={c.field}>
              <label htmlFor="pcName">{tx("nameOfThePersonGiving")}</label>
              <input id="pcName" className={c.input} value={name} onChange={(e) => setName(e.target.value)} autoComplete="off" />
            </div>
            <label className={c.check}>
              <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
              <span>{tx("theProcedureWasExplainedAnd")}</span>
            </label>
          </>
        )}

        {template && (
          <>
            {signable(items ?? []).length > 1 && (
              <div className={c.langs} role="group" aria-label={tx("language")}>
                {signable(items ?? []).map((t) => (
                  <button key={t.lang} type="button" lang={t.lang} className={`${c.lang} ${t.lang === lang ? c.langOn : ""}`} aria-pressed={t.lang === lang}
                    onClick={() => { setLang(t.lang); setReadToEnd(false); }}>{LANG_NAME[t.lang] ?? t.lang}</button>
                ))}
              </div>
            )}
            <strong lang={template.lang}>{template.title}</strong>
            <span className={c.muted}>{tx("formVersion", { version: template.version })}</span>
            <div ref={formRef} className={c.form} lang={template.lang} dir={template.lang === "ar" ? "rtl" : "auto"} tabIndex={0}
              onScroll={(e) => { const el = e.currentTarget; if (el.scrollTop + el.clientHeight >= el.scrollHeight - 4) setReadToEnd(true); }}>
              {plainText(template.bodyHtml)}
            </div>
            {!readToEnd && <p className={c.muted}>{tx("scrollToEnd")}</p>}

            {readToEnd && (
              <>
                {minor && (
                  <div className={`${c.notice} ${guardianMissing ? c.noticeDanger : c.noticeInfo}`} role="status">
                    <Icon name="alert" size={16} />
                    <span>{guardianMissing ? tx("minorNoGuardian") : tx("minorGuardianSigns", { guardian: patient?.guardianName ?? "" })}</span>
                  </div>
                )}
                {!minor && patient?.guardianId && (
                  <div className={c.langs} role="radiogroup" aria-label={tx("whoSigns")}>
                    <label className={`${c.check} ${c.big}`}><input type="radio" checked={kind === "patient"} onChange={() => setSigner({ kind: "patient", name: patient?.name ?? "" })} />{tx("signerPatient")}</label>
                    <label className={`${c.check} ${c.big}`}><input type="radio" checked={kind === "guardian"} onChange={() => setSigner({ kind: "guardian", name: patient?.guardianName ?? "" })} />{tx("signerGuardian")}</label>
                  </div>
                )}
                <div className={c.field}>
                  <label htmlFor="pcSigner">{tx("signerName")}</label>
                  <input id="pcSigner" className={c.input} value={name} onChange={(e) => setName(e.target.value)} autoComplete="off" />
                </div>
                <span>{tx("signHere")}</span>
                <SignaturePad label={tx("signaturePad")} strokes={strokes} onChange={setStrokes} />
                <div><button type="button" className={c.btn} onClick={() => setStrokes([])} disabled={strokes.length === 0}>{tx("clear")}</button></div>
                {minor && (
                  <>
                    <span>{tx("assentOptional")}</span>
                    <SignaturePad label={tx("assentPad")} strokes={assent} onChange={setAssent} />
                    <div><button type="button" className={c.btn} onClick={() => setAssent([])} disabled={assent.length === 0}>{tx("clear")}</button></div>
                  </>
                )}
                <p className={c.muted}>{tx("witnessIsYou")}</p>
              </>
            )}
          </>
        )}
        {error && <div className={`${c.notice} ${c.noticeDanger}`} role="alert"><Icon name="alert" size={16} /><span>{error}</span></div>}
      </div>
      <div className={c.dialogActions}>
        <button type="button" className={c.btn} onClick={onClose}>{tx("cancel")}</button>
        {template
          ? <button type="button" className={c.btnPrimary} onClick={() => void submit()} disabled={busy || !canSign}>{busy ? tx("recording") : tx("submitSigned")}</button>
          : items !== null && <button type="button" className={c.btnPrimary} onClick={() => void submitTyped()} disabled={busy || !name.trim() || !confirmed}>{busy ? tx("recording") : tx("recordConsent")}</button>}
      </div>
    </Dialog>
  );
}
