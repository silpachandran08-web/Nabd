"use client";

// FEF-030: transfer to another department, moved out of consult/page.tsx.
import { useState } from "react";
import type { AuthedFetch, Problem } from "../shared";
import type { TransferTarget } from "../visit/types";

export function useTransfer(authedFetch: AuthedFetch, entry: { id: string; departmentId: string }, onDone: () => void) {
  const [showTransfer, setShowTransfer] = useState(false);
  const [transferTargets, setTransferTargets] = useState<TransferTarget[] | null>(null);
  const [transferDepartmentId, setTransferDepartmentId] = useState("");
  const [transferDoctorId, setTransferDoctorId] = useState("");
  const [transferReason, setTransferReason] = useState(""); // REC-021: server requires a reason for a department move
  const [transferNote, setTransferNote] = useState("");
  const [transferring, setTransferring] = useState(false);
  const [transferError, setTransferError] = useState<string | null>(null);

  async function openTransfer() {
    setTransferError(null);
    setTransferDepartmentId("");
    setTransferDoctorId("");
    setTransferReason("");
    setTransferNote("");
    setTransferTargets(null);
    setShowTransfer(true);
    const res = await authedFetch(`/departments/${entry.departmentId}/transfer-targets`);
    if (!res?.ok) {
      setTransferError("Couldn't load transfer options.");
      setTransferTargets([]);
      return;
    }
    setTransferTargets(await res.json());
  }

  async function submitTransfer() {
    if (!transferDepartmentId || !transferDoctorId || !transferReason) return;
    setTransferring(true);
    setTransferError(null);
    try {
      const res = await authedFetch(`/queue/${entry.id}/transfer`, {
        method: "POST",
        body: JSON.stringify({ toDepartmentId: transferDepartmentId, doctorId: transferDoctorId, reason: transferReason, ...(transferReason === "other" && transferNote.trim() ? { reasonNote: transferNote.trim() } : {}) }),
      });
      if (!res) return;
      if (!res.ok) {
        const pr: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't transfer the patient." }));
        setTransferError(pr.detail || "Couldn't transfer the patient.");
        return;
      }
      setShowTransfer(false);
      onDone();
    } finally {
      setTransferring(false);
    }
  }
  return {
    showTransfer, transferTargets, transferDepartmentId, transferDoctorId, transferReason, transferNote, transferring, transferError,
    setShowTransfer, setTransferDepartmentId, setTransferDoctorId, setTransferReason, setTransferNote, openTransfer, submitTransfer,
  };
}
export type Transfer = ReturnType<typeof useTransfer>;
