"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./dpa.module.css";
import wizard from "../../(clinic)/setup/wizard/wizard.module.css";
import { ErrorState, Skeleton } from "../ui/StateViews";
import { api, reasonText } from "../../lib/api";
import { can } from "../../lib/permissions";
import { canGoLive, checkText, openBlockers, type GoLiveChecks, type GoLiveResult } from "../../lib/trial";

/** ADM-011: the go-live step. The server decides what blocks; this only shows it and posts the button. */
export default function GoLivePanel() {
  const router = useRouter();
  const [checks, setChecks] = useState<GoLiveChecks | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [fail, setFail] = useState<string | null>(null);
  const [live, setLive] = useState<GoLiveResult | null>(null);

  const load = useCallback(async () => {
    try { setChecks(await api.get<GoLiveChecks>("/setup/go-live/checks")); setError(null); }
    catch (e) { setError(reasonText(e, "Could not run the checks.")); }
    finally { setLoading(false); }
  }, []);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- initial load; state is set after the awaited fetch
  useEffect(() => { void load(); }, [load]);

  if (loading) return <Skeleton rows={4} label="Running checks" />;
  if (error || !checks) return <ErrorState message={error ?? "Checks unavailable"} onRetry={() => { setLoading(true); void load(); }} />;

  async function goLive() {
    setBusy(true); setFail(null);
    try { setLive(await api.post<GoLiveResult>("/setup/go-live", {})); }
    catch (e) { setFail(reasonText(e, "Could not go live.")); void load(); }
    setBusy(false);
  }

  async function poster() {
    let blob: Blob;
    try { blob = await api.get<Blob>("/setup/go-live/poster", { as: "blob" }); } catch { setFail("Could not download the poster."); return; }
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url; a.download = "booking-poster.pdf"; a.click();
    URL.revokeObjectURL(url);
  }

  if (live) {
    return (
      <div className={styles.panel}>
        <p className={styles.ok} role="status">You are live. Patients can book now.</p>
        <p className={wizard.text}>Booking link: <a href={live.bookingUrl}>{live.bookingUrl}</a></p>
        <div className={styles.row}>
          <button type="button" className={styles.btn} onClick={poster}>Print poster</button>
          <button type="button" className={styles.btn} onClick={() => router.push("/schedule")}>Do a test booking first</button>
        </div>
        {fail && <p className={styles.err} role="alert">{fail}</p>}
      </div>
    );
  }

  const open = openBlockers(checks);
  const mayEdit = can("setup:edit");
  return (
    <div className={styles.panel}>
      {open.length === 0
        ? <p className={styles.ok}>Everything required is in place.</p>
        : <>
            <p className={wizard.text}>These must be done before going live:</p>
            <ul className={styles.blockers}>
              {open.map((b) => <li key={b.code}>{checkText(b.code)}{b.fixUrl && <> {" "}<a href={b.fixUrl}>Fix</a></>}</li>)}
            </ul>
          </>}
      {checks.niceToHave.length > 0 && (
        <>
          <p className={wizard.text}>Good to have (not required):</p>
          <ul className={styles.blockers}>
            {checks.niceToHave.map((n) => <li key={n.code}>{n.ok ? "Done: " : "Not yet: "}{checkText(n.code)}{!n.ok && n.note ? ` (${n.note})` : ""}</li>)}
          </ul>
        </>
      )}
      <div className={styles.row}>
        <button type="button" className={styles.btn} onClick={goLive} disabled={busy || !canGoLive(checks) || !mayEdit}>Go live</button>
      </div>
      {!mayEdit && <p className={wizard.text}>Only the clinic owner can go live.</p>}
      {fail && <p className={styles.err} role="alert">{fail}</p>}
    </div>
  );
}
