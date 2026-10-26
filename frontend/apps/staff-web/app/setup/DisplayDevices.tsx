"use client";

// ACC-023: "Waiting-room display" under Setup: open a pairing code, see the paired screens, unpair one. setup:edit changes; setup:view lists.
import { useCallback, useEffect, useState } from "react";
import styles from "./setup.module.css";
import { api, errorText } from "../lib/api";
import { can } from "../lib/permissions";
import { useHasMounted } from "../lib/useHasMounted";
import { EmptyState, ErrorState, Skeleton } from "../components/ui/StateViews";

type Device = { id: string; label: string; pairedAt: string; lastSeenAt?: string | null };
type Code = { code: string; expiresAt: string; clinic: string };

export default function DisplayDevices() {
  const mounted = useHasMounted();
  const canEdit = mounted && can("setup:edit");
  const [devices, setDevices] = useState<Device[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [label, setLabel] = useState("Waiting room");
  const [code, setCode] = useState<Code | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try { setDevices(await api.get<Device[]>("/display-devices")); } catch (e) { setError(errorText(e, "Couldn't load the paired screens.")); }
  }, []);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  async function newCode(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setMsg(null);
    try { setCode(await api.post<Code>("/display-devices/pairing-codes", { scope: {}, label: label.trim() || "Waiting room" })); }
    catch (err) { setMsg(errorText(err, "Couldn't create a code.")); }
    finally { setBusy(false); }
  }
  async function unpair(id: string) {
    setMsg(null);
    try { await api.delete(`/display-devices/${id}`); await load(); } catch (err) { setMsg(errorText(err, "Couldn't unpair this screen.")); }
  }

  const url = code && typeof window !== "undefined" ? `${window.location.origin}/display?clinic=${encodeURIComponent(code.clinic)}` : "";
  return (
    <div className={styles.card} data-testid="display-devices">
      <h2 className={styles.cardTitle}>Waiting-room display</h2>
      <p className={styles.subtitle}>Shows token numbers only, never patient names. Open the address on the TV and type the code.</p>
      {canEdit && (
        <form onSubmit={newCode}>
          <label htmlFor="display-label" className={styles.label}>Screen name</label>
          <input id="display-label" className={styles.input} value={label} maxLength={60} onChange={(e) => setLabel(e.target.value)} />
          <button type="submit" className={styles.wizardBtn} disabled={busy}>Show pairing code</button>
        </form>
      )}
      {code && (
        <div role="status" data-testid="pairing-code">
          <p><strong style={{ fontSize: 28, letterSpacing: 4 }}>{code.code}</strong></p>
          <p>Valid for 10 minutes, works once. Open <code>{url}</code></p>
        </div>
      )}
      {msg && <div className={styles.errorState} role="alert">{msg}</div>}
      {error ? <ErrorState message={error} onRetry={() => void load()} /> : devices === null ? <Skeleton rows={2} label="Loading paired screens" /> : devices.length === 0 ? (
        <EmptyState icon="inbox" title="No screens paired" hint="Show a pairing code, then enter it on the TV." />
      ) : (
        <table className={styles.table}>
          <thead><tr><th scope="col">Screen</th><th scope="col">Paired</th><th scope="col">Last seen</th>{canEdit && <th scope="col"><span className="sr-only">Actions</span></th>}</tr></thead>
          <tbody>
            {devices.map((d) => (
              <tr key={d.id}>
                <td>{d.label}</td>
                <td>{d.pairedAt.slice(0, 16).replace("T", " ")}</td>
                <td>{d.lastSeenAt ? d.lastSeenAt.slice(0, 16).replace("T", " ") : "-"}</td>
                {canEdit && <td><button type="button" className={styles.wizardBtn} onClick={() => void unpair(d.id)}>Unpair</button></td>}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
