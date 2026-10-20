"use client";

import { useCallback, useEffect, useState } from "react";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import { Skeleton } from "../ui/StateViews";
import type { AuthedFetch, Problem } from "./shared";
import s from "./consultParts.module.css";
import d from "./dose.module.css";

// GET /packages/instances?patientId= (InstanceResponse) and POST /packages/instances/items/{id}/redeem.
type Item = { id: string; name: string; quantityTotal: number; quantityConsumed: number };
type Instance = { id: string; packageName: string; patientId: string; status: string; validityEnd: string | null; items: Item[] };

type Pending = { instance: Instance; item: Item };
type Done = { itemName: string; consumed: number; total: number; nextVisit: string };

/** DOC-041: complete one package session from the consult. Redeem is the only call that consumes a session
 * (booking does not), so it sits behind an explicit confirmation that the service has been delivered. The server has no
 * recommended-interval data for a package, so none is shown; the doctor may type a next-visit interval as free text. */
export default function PackageSessionCard({ authedFetch, patientId, canRedeem }: { authedFetch: AuthedFetch; patientId: string; canRedeem: boolean }) {
  const [state, setState] = useState<"loading" | "ok" | "error">("loading");
  const [instances, setInstances] = useState<Instance[]>([]);
  const [pending, setPending] = useState<Pending | null>(null);
  const [delivered, setDelivered] = useState(false);
  const [nextVisit, setNextVisit] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<Done | null>(null);

  const load = useCallback(async () => {
    try {
      // DOC-R6: the server filters by patient; no client-side filter over the whole tenant list.
      const res = await authedFetch(`/packages/instances?patientId=${encodeURIComponent(patientId)}`);
      if (!res) return;
      if (!res.ok) { setState("error"); return; }
      setInstances(await res.json());
      setState("ok");
    } catch {
      setState("error");
    }
  }, [authedFetch, patientId]);

  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(() => { if (!cancelled) { setState("loading"); setDone(null); void load(); } });
    return () => { cancelled = true; };
  }, [load]);

  function open(p: Pending) {
    setPending(p); setDelivered(false); setNextVisit(""); setError(null);
  }
  function close() {
    if (busy) return;
    setPending(null);
  }

  async function confirm() {
    if (!pending || busy) return;
    setBusy(true);
    setError(null);
    try {
      const res = await authedFetch(`/packages/instances/items/${pending.item.id}/redeem`, { method: "POST" });
      if (!res) return;
      if (!res.ok) {
        const pr: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't complete the session." }));
        setError(pr.detail || "Couldn't complete the session.");
        return;
      }
      const updated: Instance = await res.json();
      const it = updated.items.find((x) => x.id === pending.item.id);
      setDone({ itemName: pending.item.name, consumed: it?.quantityConsumed ?? pending.item.quantityConsumed + 1, total: it?.quantityTotal ?? pending.item.quantityTotal, nextVisit: nextVisit.trim() });
      setPending(null);
      await load();
    } catch {
      setError("Couldn't complete the session. Nothing was confirmed; check the package ledger before trying again.");
    } finally {
      setBusy(false);
    }
  }

  const live = instances.filter((i) => (i.status === "active" || i.status === "grace") && i.items.some((x) => x.quantityConsumed < x.quantityTotal));

  return (
    <div className={d.wrap} aria-label="Package sessions">
      <div className={d.head}>
        <span className={d.title}>Complete a package session</span>
        <StatusPill tone="info" icon="package" label="Consumed after the service" />
      </div>
      <p className={d.note}>A session is consumed only after the service has been delivered. Completing it here uses the same redeem step as the Packages page.</p>

      {state === "loading" && <Skeleton rows={2} label="Loading packages" />}
      {state === "error" && (
        <div className={`${s.notice} ${s.noticeDanger}`} role="alert">
          <Icon name="alert" size={16} />
          <span>Couldn&apos;t load this patient&apos;s packages. <button type="button" className={s.linkBtn} onClick={() => { setState("loading"); void load(); }}>Retry</button></span>
        </div>
      )}
      {state === "ok" && live.length === 0 && <p className={s.muted}>No package with sessions left to complete.</p>}
      {state === "ok" && live.map((inst) => (
        <div key={inst.id} className={d.pkgItem}>
          <div className={d.pkgRow}>
            <strong className={d.pkgName}>{inst.packageName}</strong>
            <StatusPill tone={inst.status === "grace" ? "warn" : "ok"} label={inst.status === "grace" ? "Grace period" : "Active"} />
          </div>
          {inst.items.map((it) => {
            const left = it.quantityTotal - it.quantityConsumed;
            return (
              <div key={it.id} className={d.pkgRow}>
                <span className={d.pkgName}>{it.name} · {it.quantityConsumed}/{it.quantityTotal} used</span>
                {canRedeem && left > 0 && <button type="button" className={s.btn} onClick={() => open({ instance: inst, item: it })}>Complete session</button>}
                {left <= 0 && <StatusPill tone="neutral" label="All used" />}
              </div>
            );
          })}
        </div>
      ))}

      {done && (
        <div className={`${s.notice} ${s.noticeOk}`} role="status">
          <Icon name="check" size={16} />
          <div>
            <strong>Session completed.</strong> {done.itemName}: {done.consumed} of {done.total} used.
            {done.nextVisit
              ? <div>Next visit noted: {done.nextVisit}. This note is not stored on the package; book the follow-up in the Follow-up panel.</div>
              : <div className={d.hint}>No recommended interval is defined for this package. Book any follow-up in the Follow-up panel.</div>}
          </div>
        </div>
      )}

      {pending && (
        <Dialog title="Complete package session" onClose={close}>
          <p>Confirm that <strong>{pending.item.name}</strong> ({pending.instance.packageName}) has been <strong>delivered to the patient</strong> in this visit.</p>
          <p className={s.muted}>This consumes 1 session: {pending.item.quantityConsumed} of {pending.item.quantityTotal} used becomes {pending.item.quantityConsumed + 1} of {pending.item.quantityTotal}. It is written to the package ledger and cannot be undone from here.</p>
          <label className={s.check}>
            <input type="checkbox" checked={delivered} onChange={(e) => setDelivered(e.target.checked)} />
            <span>The service has been delivered.</span>
          </label>
          <p className={s.muted}>Recommended next interval: none is defined for this package.</p>
          <label className={d.label}>
            Next-visit interval (optional, your own wording)
            <input className={s.input} maxLength={80} value={nextVisit} onChange={(e) => setNextVisit(e.target.value)} placeholder="e.g. 2 weeks" />
          </label>
          {error && <div className={`${s.notice} ${s.noticeDanger}`} role="alert"><Icon name="alert" size={16} /><span>{error}</span></div>}
          <div className={s.dialogActions}>
            <button type="button" className={s.btn} onClick={close} disabled={busy}>Cancel</button>
            <button type="button" className={s.btnPrimary} onClick={() => void confirm()} disabled={!delivered || busy}>{busy ? "Completing…" : "Complete session"}</button>
          </div>
        </Dialog>
      )}
    </div>
  );
}
