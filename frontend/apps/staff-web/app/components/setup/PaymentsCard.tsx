"use client";

// FIN-019: the clinic's own payment gateway (Razorpay). Connect and disconnect need setup:edit plus a fresh authenticator code; the keys are
// sent once and never shown again. Without setup:edit the controls are absent.
import { useCallback, useEffect, useState } from "react";
import styles from "../../(clinic)/setup/setup.module.css";
import { ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";
import { api, reasonText } from "../../lib/api";
import { requestStepUpToken } from "../../lib/stepUp";

type Account = { accountId: string; provider: string; status: string; connectedAt: string | null; lastTestAt: string | null };
type Current = { connected: boolean; account: Account | null };

export default function PaymentsCard() {
  const [cur, setCur] = useState<Current | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [mode, setMode] = useState("test");
  const [keyId, setKeyId] = useState("");
  const [keySecret, setKeySecret] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [link, setLink] = useState<string | null>(null);
  const edit = can("setup:edit");

  const load = useCallback(async () => {
    try { setCur(await api.get<Current>("/billing/gateway-accounts/current")); setError(null); }
    catch (e) { setError(reasonText(e, "Could not load the payment settings.")); }
  }, []);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- initial load; state is set after the awaited fetch
  useEffect(() => { void load(); }, [load]);

  if (error && !cur) return <ErrorState message={error} onRetry={() => void load()} />;
  if (!cur) return <Skeleton rows={3} label="Loading payment settings" />;

  async function connect() {
    setBusy(true); setMsg(null);
    const su = await requestStepUpToken(code);
    if ("error" in su) { setMsg(su.error); setBusy(false); return; }
    try {
      await api.post("/billing/gateway-accounts", { provider: "razorpay", mode, keys: { keyId: keyId.trim(), keySecret } }, { headers: { "X-Step-Up-Token": su.token } });
      setKeyId(""); setKeySecret(""); setCode(""); setMsg("Connected."); await load();
    } catch (e) { setMsg(reasonText(e, "Could not connect the gateway.")); }
    setBusy(false);
  }
  async function testPayment(id: string) {
    setBusy(true); setMsg(null); setLink(null);
    try { const r = await api.post<{ paymentLinkUrl: string }>(`/billing/gateway-accounts/${id}/test-payment`, {}); setLink(r.paymentLinkUrl); setMsg("Test payment link created for Rs 1. Pay it, then refund it from the gateway dashboard."); await load(); }
    catch (e) { setMsg(reasonText(e, "The test payment failed.")); }
    setBusy(false);
  }
  async function disconnect(id: string) {
    setBusy(true); setMsg(null);
    const su = await requestStepUpToken(code);
    if ("error" in su) { setMsg(su.error); setBusy(false); return; }
    try { await api.delete(`/billing/gateway-accounts/${id}`, { headers: { "X-Step-Up-Token": su.token } }); setCode(""); setMsg("Disconnected."); await load(); }
    catch (e) { setMsg(reasonText(e, "Could not disconnect.")); }
    setBusy(false);
  }

  const a = cur.account;
  return (
    <div className={styles.card}>
      <h2 className={styles.cardTitle}>Payments</h2>
      {cur.connected && a ? (
        <>
          <p className={styles.hint}>Razorpay is {a.status}. {a.lastTestAt ? `Last test ${new Date(a.lastTestAt).toLocaleString()}.` : "Not tested yet."}</p>
          {edit && (
            <>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="pay-code-d">Code to disconnect</label>
                <input id="pay-code-d" className={styles.input} inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} />
              </div>
              <div className={styles.actions}>
                <button className={styles.btn} style={{ minBlockSize: 44 }} disabled={busy} onClick={() => void testPayment(a.accountId)}>Send test payment</button>
                <button className={styles.btn} style={{ minBlockSize: 44 }} disabled={busy || code.length < 6} onClick={() => void disconnect(a.accountId)}>Disconnect</button>
              </div>
            </>
          )}
        </>
      ) : (
        <>
          <p className={styles.hint}>Not connected. Payment-link buttons stay hidden at checkout until a gateway is connected.</p>
          {edit && (
            <>
              <div className={styles.row}>
                <div className={styles.field}><label className={styles.label} htmlFor="pay-mode">Mode</label>
                  <select id="pay-mode" className={styles.select} value={mode} onChange={(e) => setMode(e.target.value)}><option value="test">Test</option><option value="live">Live</option></select></div>
                <div className={styles.field}><label className={styles.label} htmlFor="pay-key">Key id</label>
                  <input id="pay-key" className={styles.input} autoComplete="off" value={keyId} onChange={(e) => setKeyId(e.target.value)} /></div>
                <div className={styles.field}><label className={styles.label} htmlFor="pay-secret">Key secret</label>
                  <input id="pay-secret" className={styles.input} type="password" autoComplete="off" value={keySecret} onChange={(e) => setKeySecret(e.target.value)} /></div>
                <div className={styles.field}><label className={styles.label} htmlFor="pay-code">Code to connect</label>
                  <input id="pay-code" className={styles.input} inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} /></div>
              </div>
              <div className={styles.actions}>
                <button className={styles.btnPrimary} style={{ minBlockSize: 44 }} disabled={busy || !keyId.trim() || !keySecret || code.length < 6} onClick={() => void connect()}>Connect Razorpay</button>
              </div>
            </>
          )}
        </>
      )}
      {msg && <div role="status" className={styles.hint}>{msg}</div>}
      {link && <p className={styles.hint}><a href={link} target="_blank" rel="noreferrer">Open test payment link</a></p>}
    </div>
  );
}
