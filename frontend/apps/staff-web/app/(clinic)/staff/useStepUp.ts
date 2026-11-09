"use client";

// FEF-032: the step-up flow of Staff & Access (suspend, authenticator reset), moved unchanged from the old single page.
import { useEffect, useRef, useState, type Dispatch, type SetStateAction } from "react";
import { api, reasonText, setStepUpHandler } from "../../lib/api";
import type { Staff } from "./staffModel";

export function useStepUp({ setError, setStaff }: { setError: (e: string | null) => void; setStaff: Dispatch<SetStateAction<Staff[]>> }) {
  const [stepUpFor, setStepUpFor] = useState<string | null>(null);
  const [stepUpKind, setStepUpKind] = useState<"suspend" | "reset">("suspend"); // PLT-004: same step-up prompt serves the MFA reset
  const [resetNotice, setResetNotice] = useState<string | null>(null);
  const [stepUpCode, setStepUpCode] = useState("");
  const [stepUpBusy, setStepUpBusy] = useState(false);
  const [stepUpError, setStepUpError] = useState<string | null>(null);
  const [suspending, setSuspending] = useState<string | null>(null);

  // FEF-018 step-up: the client asks this when a call answers "step-up-required"; the dialog resolves with a step-up
  // token (the client then replays the original request once) or null (Cancel).
  const stepUpWait = useRef<((token: string | null) => void) | null>(null);
  const stepUpTarget = useRef<string | null>(null);
  const stepUpCancelled = useRef(false);
  const stepUpShown = useRef(false);
  const askStepUp = useRef<() => Promise<string | null>>(() => Promise.resolve(null));
  useEffect(() => {
    askStepUp.current = () => new Promise<string | null>((resolve) => {
      stepUpWait.current = resolve;
      stepUpShown.current = true;
      setStepUpFor(stepUpTarget.current);
      setStepUpCode("");
      setStepUpError(null);
    });
    setStepUpHandler(() => askStepUp.current());
    return () => setStepUpHandler(null);
  }, []);
  function cancelStepUp() {
    stepUpCancelled.current = true;
    stepUpWait.current?.(null);
    stepUpWait.current = null;
    setStepUpFor(null);
  }

  // Suspend and authenticator reset both need a step-up: the client middleware asks the dialog below, then replays
  // the call once with the token. A wrong code keeps the dialog open; Cancel ends the action quietly.
  async function stepUpAction(kind: "suspend" | "reset", id: string, preset?: string) {
    const reset = kind === "reset";
    setError(null);
    setResetNotice(null);
    setStepUpKind(kind);
    stepUpTarget.current = id;
    stepUpCancelled.current = false;
    stepUpShown.current = !!preset;
    setSuspending(id);
    try {
      if (reset) {
        // Resetting an authenticator always needs a fresh code: ask first, no failed round trip (PLT-004).
        const token = preset ?? (await askStepUp.current());
        if (!token) return;
        await api.post(`/staff/${id}/mfa/reset`, { reasonCode: "lost_device" }, { headers: { "X-Step-Up-Token": token } });
      } else await api.post(`/staff/${id}/suspend`, undefined, preset ? { headers: { "X-Step-Up-Token": preset } } : undefined);
      if (reset) setResetNotice("Authenticator reset. They are signed out and must set up a new one at next sign-in.");
      else setStaff((prev) => prev.map((s) => (s.id === id ? { ...s, status: "suspended" } : s)));
      setStepUpFor(null);
    } catch (e) {
      if (stepUpCancelled.current) return;
      const text = reasonText(e, reset ? "Couldn't reset the authenticator." : "Couldn't suspend staff member.");
      if (stepUpShown.current) setStepUpError(text);
      else setError(text);
    } finally {
      stepUpWait.current = null;
      setStepUpBusy(false);
      setSuspending(null);
    }
  }
  const suspend = (id: string) => stepUpAction("suspend", id);
  const startReset = (id: string) => stepUpAction("reset", id);

  async function confirmStepUp(e: React.FormEvent) {
    e.preventDefault();
    if (!stepUpFor) return;
    setStepUpBusy(true);
    setStepUpError(null);
    try {
      // keepSession: a wrong code also answers 401 ("mfa-failed"); that must not sign the caller out mid-action.
      const { stepUpToken } = await api.post<{ stepUpToken: string }>("/auth/mfa/verify", { challengeId: "", code: stepUpCode }, { keepSession: true });
      if (stepUpWait.current) {
        stepUpWait.current(stepUpToken); // the original request is replayed by the client; stepUpAction finishes the flow
        stepUpWait.current = null;
      } else await stepUpAction(stepUpKind, stepUpFor, stepUpToken); // the action already failed and the dialog stayed open: retry with the new token
    } catch (err) {
      setStepUpError(reasonText(err, "Verification failed."));
      setStepUpBusy(false);
    }
  }

  return { stepUpFor, setStepUpFor, stepUpKind, resetNotice, stepUpCode, setStepUpCode, stepUpBusy, stepUpError, suspending, cancelStepUp, suspend, startReset, confirmStepUp };
}
