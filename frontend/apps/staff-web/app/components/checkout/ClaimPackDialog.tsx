"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { api, ApiError, intentKeys } from "../../lib/api";
import Dialog from "../ui/Dialog";
import { ErrorState, Skeleton } from "../ui/StateViews";
import styles from "../../(clinic)/checkout/checkout.module.css";

type Format = { code: string; insurerName: string; jurisdiction: string | null; sections: string[]; csv: boolean };
type Pack = { packId: string; documentUrl: string; csvUrl?: string | null };

/** The API returns "/v1/..." paths; the client's base URL already ends in /v1. */
export const apiPath = (url: string) => url.replace(/^\/v1(?=\/)/, "");

// FIN-035: claim pack for a paid visit. POST /billing/claim-packs (billing:view; the consultation summary inside also needs
// clinical:view, so the caller only shows this to roles with both). The patient copy goes only by OTP secure link (IDC-015).
export default function ClaimPackDialog({ visitId, onClose }: { visitId: string; onClose: () => void }) {
  const t = useTranslations("checkout.claimPack");
  const keys = useRef(intentKeys());
  const [formats, setFormats] = useState<Format[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [code, setCode] = useState("");
  const [pack, setPack] = useState<Pack | null>(null);
  const [busy, setBusy] = useState<"gen" | "send" | null>(null);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  const load = useCallback(async () => {
    setLoadError(false);
    try {
      const r = await api.get<{ items: Format[] }>("/billing/claim-pack-formats");
      setFormats(r.items);
      setCode((c) => c || r.items[0]?.code || "");
    } catch { setLoadError(true); }
  }, []);
  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(() => { void load(); }, [load]);

  const fail = (e: unknown, fallback: string) => setMsg({ ok: false, text: e instanceof ApiError && e.detail ? e.detail : fallback });

  async function generate() {
    const body = { visitId, formatCode: code };
    setBusy("gen"); setMsg(null); setPack(null);
    try {
      setPack(await api.post<Pack>("/billing/claim-packs", body, { idempotencyKey: keys.current.get("gen", body) }));
      keys.current.done("gen");
    } catch (e) { fail(e, t("failed")); } finally { setBusy(null); }
  }

  async function download(url: string, name: string) {
    try {
      const blob = await api.get<Blob>(apiPath(url), { as: "blob" });
      const href = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = href; a.download = name; a.click();
      URL.revokeObjectURL(href);
    } catch (e) { fail(e, t("downloadFailed")); }
  }

  async function send() {
    if (!pack) return;
    const body = {};
    setBusy("send"); setMsg(null);
    try {
      await api.post(`/billing/claim-packs/${pack.packId}/send`, body, { idempotencyKey: keys.current.get(`send-${pack.packId}`, body) });
      keys.current.done(`send-${pack.packId}`);
      setMsg({ ok: true, text: t("sent") });
    } catch (e) { fail(e, t("sendFailed")); } finally { setBusy(null); }
  }

  return (
    <Dialog title={t("title")} onClose={onClose}>
      <p className={styles.muted}>{t("contents")}</p>
      {formats === null && !loadError && <Skeleton rows={2} label={t("loadingFormats")} />}
      {loadError && <ErrorState message={t("couldNotLoadFormats")} onRetry={() => void load()} retryLabel={t("retry")} />}
      {formats !== null && formats.length === 0 && <p className={styles.muted}>{t("noFormats")}</p>}
      {formats !== null && formats.length > 0 && (
        <label className={styles.muted}>
          {t("format")}{" "}
          <select value={code} onChange={(e) => { setCode(e.target.value); setPack(null); }} disabled={busy !== null}>
            {formats.map((f) => <option key={f.code} value={f.code}>{f.insurerName}{f.csv ? " (PDF + CSV)" : " (PDF)"}</option>)}
          </select>
        </label>
      )}
      <div className={styles.docActions}>
        {formats !== null && formats.length > 0 && !pack && (
          <button type="button" onClick={() => void generate()} disabled={!code || busy !== null}>{busy === "gen" ? t("generating") : t("generate")}</button>
        )}
        {pack && <button type="button" onClick={() => void download(pack.documentUrl, `claim-pack-${pack.packId}.pdf`)}>{t("download")}</button>}
        {pack?.csvUrl && <button type="button" onClick={() => void download(pack.csvUrl!, `claim-pack-${pack.packId}.csv`)}>{t("downloadCsv")}</button>}
        {pack && <button type="button" onClick={() => void send()} disabled={busy !== null}>{busy === "send" ? t("sending") : t("send")}</button>}
        <button type="button" onClick={onClose}>{t("close")}</button>
      </div>
      {msg && <div role={msg.ok ? "status" : "alert"} className={styles.muted}>{msg.text}</div>}
    </Dialog>
  );
}
