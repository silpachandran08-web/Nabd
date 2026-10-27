"use client";

import { useTranslations } from "next-intl";
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
  const tx = useTranslations("consult.packageSessionCard");
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
        const pr: Problem = await res.json().catch(() => ({ title: "Error", detail: tx("couldnTCompleteTheSession") }));
        setError(pr.detail || tx("couldnTCompleteTheSession"));
        return;
      }
      const updated: Instance = await res.json();
      const it = updated.items.find((x) => x.id === pending.item.id);
      setDone({ itemName: pending.item.name, consumed: it?.quantityConsumed ?? pending.item.quantityConsumed + 1, total: it?.quantityTotal ?? pending.item.quantityTotal, nextVisit: nextVisit.trim() });
      setPending(null);
      await load();
    } catch {
      setError(tx("couldnTCompleteTheSession2"));
    } finally {
      setBusy(false);
    }
  }

  const live = instances.filter((i) => (i.status === "active" || i.status === "grace") && i.items.some((x) => x.quantityConsumed < x.quantityTotal));

  return (
    <div className={d.wrap} aria-label={tx("packageSessions")}>
      <div className={d.head}>
        <span className={d.title}>{tx("completeAPackageSession")}</span>
        <StatusPill tone="info" icon="package" label={tx("consumedAfterTheService")} />
      </div>
      <p className={d.note}>{tx("aSessionIsConsumedOnly")}</p>

      {state === "loading" && <Skeleton rows={2} label={tx("loadingPackages")} />}
      {state === "error" && (
        <div className={`${s.notice} ${s.noticeDanger}`} role="alert">
          <Icon name="alert" size={16} />
          <span>{tx("couldnTLoadThisPatient")}{" "}<button type="button" className={s.linkBtn} onClick={() => { setState("loading"); void load(); }}>{tx("retry")}</button></span>
        </div>
      )}
      {state === "ok" && live.length === 0 && <p className={s.muted}>{tx("noPackageWithSessionsLeft")}</p>}
      {state === "ok" && live.map((inst) => (
        <div key={inst.id} className={d.pkgItem}>
          <div className={d.pkgRow}>
            <strong className={d.pkgName}>{inst.packageName}</strong>
            <StatusPill tone={inst.status === "grace" ? "warn" : "ok"} label={inst.status === "grace" ? tx("gracePeriod") : tx("active")} />
          </div>
          {inst.items.map((it) => {
            const left = it.quantityTotal - it.quantityConsumed;
            return (
              <div key={it.id} className={d.pkgRow}>
                <span className={d.pkgName}>{tx("used", { name: it.name, quantityConsumed: it.quantityConsumed, quantityTotal: it.quantityTotal })}</span>
                {canRedeem && left > 0 && <button type="button" className={s.btn} onClick={() => open({ instance: inst, item: it })}>{tx("completeSession")}</button>}
                {left <= 0 && <StatusPill tone="neutral" label={tx("allUsed")} />}
              </div>
            );
          })}
        </div>
      ))}

      {done && (
        <div className={`${s.notice} ${s.noticeOk}`} role="status">
          <Icon name="check" size={16} />
          <div>
            <strong>{tx("sessionCompleted")}</strong> {tx("ofUsed", { itemName: done.itemName, consumed: done.consumed, total: done.total })}
            {done.nextVisit
              ? <div>{tx("nextVisitNotedThisNote", { nextVisit: done.nextVisit })}</div>
              : <div className={d.hint}>{tx("noRecommendedIntervalIsDefined")}</div>}
          </div>
        </div>
      )}

      {pending && (
        <Dialog title={tx("completePackageSession")} onClose={close}>
          <p>{tx("confirmThat")}{" "}<strong>{pending.item.name}</strong>{" "}{tx("hasBeen", { packageName: pending.instance.packageName })}{" "}<strong>{tx("deliveredToThePatient")}</strong>{" "}{tx("inThisVisit")}</p>
          <p className={s.muted}>{tx("thisConsumes1SessionOf", { quantityConsumed: pending.item.quantityConsumed, quantityTotal: pending.item.quantityTotal })}{" "}{pending.item.quantityConsumed + 1} {" "}{tx("ofItIsWrittenTo", { quantityTotal: pending.item.quantityTotal })}</p>
          <label className={s.check}>
            <input type="checkbox" checked={delivered} onChange={(e) => setDelivered(e.target.checked)} />
            <span>{tx("theServiceHasBeenDelivered")}</span>
          </label>
          <p className={s.muted}>{tx("recommendedNextIntervalNoneIs")}</p>
          <label className={d.label}>
            {tx("nextVisitIntervalOptionalYour")}
            <input className={s.input} maxLength={80} value={nextVisit} onChange={(e) => setNextVisit(e.target.value)} placeholder={tx("eG2Weeks")} />
          </label>
          {error && <div className={`${s.notice} ${s.noticeDanger}`} role="alert"><Icon name="alert" size={16} /><span>{error}</span></div>}
          <div className={s.dialogActions}>
            <button type="button" className={s.btn} onClick={close} disabled={busy}>{tx("cancel")}</button>
            <button type="button" className={s.btnPrimary} onClick={() => void confirm()} disabled={!delivered || busy}>{busy ? tx("completing") : tx("completeSession")}</button>
          </div>
        </Dialog>
      )}
    </div>
  );
}
