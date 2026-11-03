"use client";

// FEF-032: the writes on a sold package (book, redeem, extend, refund request / decision, reminder), moved unchanged
// from the old single page. A refusal shows beside its field / in the page summary; the screen is always re-read after.
import { useRef, useState } from "react";
import { api, ApiError } from "../../lib/api";
import type { FormErrors } from "../../lib/formErrors";
import type { Full } from "../../lib/gen";
import type { RefundPreviewResponse } from "@nabd/api-client/generated";
import { why, type InstanceT, type RefundT, type Tab } from "./packagesModel";

export type Deciding = { refund: RefundT; current: number; owed: number; changed: boolean };

export function useInstanceActions({ fe, loadAll, setTab }: { fe: FormErrors; loadAll: () => Promise<unknown>; setTab: (t: Tab) => void }) {
  const [selectedInstanceId, setSelectedInstanceId] = useState<string | null>(null);
  const [selectedInstance, setSelectedInstance] = useState<InstanceT | null>(null);
  const [extendDate, setExtendDate] = useState("");
  const [extendReason, setExtendReason] = useState("");
  const [refundPreview, setRefundPreview] = useState<Full<RefundPreviewResponse, never> | null>(null);
  const [refundReason, setRefundReason] = useState("");
  // FIN-004: the approver sees request-time vs current amount and confirms the current one (expectedAmount).
  const [deciding, setDeciding] = useState<Deciding | null>(null);
  const [rejectReason, setRejectReason] = useState("");
  const [reminderNotice, setReminderNotice] = useState<string | null>(null);
  const decideLock = useRef(false); // synchronous guard (as in checkout): a double-click must never send a second decision

  // The instances list (loadAll) never carries ledger events — only the single-instance detail
  // endpoint does — so the detail view keeps its own freshly-fetched copy instead of looking itself
  // up in that list.
  async function loadSelectedInstance(id: string) {
    try { setSelectedInstance(await api.get<InstanceT>(`/packages/instances/${id}`)); } catch { /* the detail keeps its last copy */ }
  }

  async function openInstance(id: string) {
    fe.clear();
    setSelectedInstanceId(id);
    await loadSelectedInstance(id);
  }

  function closeInstance() {
    setSelectedInstanceId(null);
    setSelectedInstance(null);
    setRefundPreview(null);
  }

  /** Run one write on an instance: a refusal shows beside its field / in the summary; the screen is always re-read after. */
  async function act(call: () => Promise<unknown>, fallback: string): Promise<boolean> {
    let ok = true;
    try { await call(); } catch (err) {
      ok = false;
      if (err instanceof ApiError && err.status === 400 && err.errors.length) fe.fail(err, fallback); else fe.set(why(err, fallback));
    }
    await loadAll();
    if (selectedInstanceId) await loadSelectedInstance(selectedInstanceId);
    return ok;
  }

  async function book(itemId: string) {
    fe.clear();
    await act(() => api.post(`/packages/instances/items/${itemId}/book`), "Action failed.");
  }

  async function redeem(itemId: string) {
    fe.clear();
    await act(() => api.post(`/packages/instances/items/${itemId}/redeem`), "Action failed.");
  }

  async function submitExtend(instanceId: string) {
    if (!extendDate || !extendReason.trim()) {
      fe.set("Pick a new date and give a reason.");
      return;
    }
    fe.clear();
    setExtendDate(""); setExtendReason("");
    await act(() => api.post(`/packages/instances/${instanceId}/extend`, { newValidityEnd: extendDate, reason: extendReason }), "Action failed.");
  }

  async function loadRefundPreview(instanceId: string) {
    try { setRefundPreview(await api.get<Full<RefundPreviewResponse>>(`/packages/instances/${instanceId}/refund-preview`)); } catch { /* the button stays; press it again */ }
  }
  async function submitRefundRequest(instanceId: string) {
    if (!refundReason.trim()) {
      fe.set("Give a refund reason.");
      return;
    }
    fe.clear();
    const reason = refundReason;
    setRefundReason(""); setRefundPreview(null);
    if (await act(() => api.post(`/packages/instances/${instanceId}/refund`, { reason }), "Action failed.")) {
      setSelectedInstanceId(null);
      setSelectedInstance(null);
      setTab("refunds");
    }
  }

  async function openDecision(r: RefundT) {
    fe.clear(); setRejectReason("");
    try {
      const p = await api.get<Full<RefundPreviewResponse>>(`/packages/instances/${r.instanceId}/refund-preview`);
      setDeciding({ refund: r, current: p.refundAmount, owed: p.amountOwed, changed: p.refundAmount !== r.refundAmount });
    } catch { fe.set("Could not load the current refund amount. Try again."); }
  }
  async function decide(fn: () => Promise<void>) {
    if (decideLock.current) return;
    decideLock.current = true;
    try { await fn(); } finally { decideLock.current = false; }
  }
  const approveRefund = () => decide(async () => {
    if (!deciding) return;
    fe.clear();
    try {
      await api.post(`/packages/refunds/${deciding.refund.id}/approve`, { expectedAmount: deciding.current });
    } catch (err) {
      const p = err instanceof ApiError && err.status === 409 ? err.problem : null;
      if (p && String(p.type).endsWith("amount-changed")) {
        setDeciding({ ...deciding, current: p.currentAmount as number, owed: (p.currentAmountOwed as number | undefined) ?? deciding.owed, changed: true });
        return;
      }
      fe.set(why(err, "Action failed."));
      await loadAll();
      return;
    }
    setDeciding(null);
    await act(async () => {}, "Action failed.");
  });
  const rejectRefund = () => decide(async () => {
    if (!deciding) return;
    if (!rejectReason.trim()) { fe.set("Give a reason to reject."); return; }
    fe.clear();
    const id = deciding.refund.id;
    const ok = await act(() => api.post(`/packages/refunds/${id}/reject`, { reason: rejectReason }), "Action failed.");
    if (ok) setDeciding(null);
  });

  async function sendReminder(instanceId: string) {
    // The endpoint only records the reminder; nothing is messaged (COM-044 wires real sending).
    setReminderNotice(null);
    if (await act(() => api.post(`/packages/instances/${instanceId}/send-reminder`), "Action failed.")) setReminderNotice("Recorded. No message was sent.");
  }

  return {
    selectedInstanceId, selectedInstance, openInstance, closeInstance, act, book, redeem,
    extendDate, setExtendDate, extendReason, setExtendReason, submitExtend,
    refundPreview, loadRefundPreview, refundReason, setRefundReason, submitRefundRequest,
    deciding, setDeciding, rejectReason, setRejectReason, openDecision, approveRefund, rejectRefund,
    reminderNotice, sendReminder,
  };
}
