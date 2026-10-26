"use client";

import { useState, type FormEvent } from "react";
import { useParams, useRouter } from "next/navigation";
import styles from "../../../../(auth)/login/login.module.css";
import { publicApi, ApiError, reasonText } from "../../../../lib/api";

// Matches POST /v1/owners/invitations/{token}/accept (OwnerController) — sets this owner's
// top-level account PIN (separate from any per-clinic staff PIN) and, like staff's own accept-invite,
// logs them straight in: here that means handing back a pending workspace token, not real clinic
// tokens, since which clinic to enter still isn't chosen yet.
type PendingWorkspaceToken = { pendingToken: string; expiresIn: number };

export default function OwnerAcceptInvitePage() {
  const router = useRouter();
  const params = useParams<{ token: string }>();
  const token = params.token;

  const [pin, setPin] = useState("");
  const [confirmPin, setConfirmPin] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    if (pin !== confirmPin) {
      setFormError("PINs don't match.");
      return;
    }
    setLoading(true);
    try {
      const body = await publicApi.post<PendingWorkspaceToken | { setupToken: string }>(`/owners/invitations/${token}/accept`, { pin }, { keepSession: true });
      if ("setupToken" in body) { // PLT-005: owners must enrol an authenticator before any workspace
        sessionStorage.setItem("nabd_owner_setup_token", body.setupToken); // read once by /owner/login
        router.replace("/owner/login");
        return;
      }
      sessionStorage.setItem("nabd_owner_pending_token", body.pendingToken);
      router.replace("/owner/workspaces");
    } catch (err) {
      setFormError(err instanceof ApiError ? err.detail || "That invite link is invalid or has expired." : reasonText(err, ""));
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <h1 className={styles.title}>Set your owner PIN</h1>
        <p className={styles.subtitle}>
          This PIN is for your own account, not any single clinic — you&apos;ll use it to switch
          between every clinic you own.
        </p>
        <form onSubmit={submit} noValidate>
          {formError && <div className={styles.formError} role="alert">{formError}</div>}
          <div className={styles.field}>
            <label className={styles.label} htmlFor="pin">New PIN</label>
            <input
              id="pin"
              type="password"
              inputMode="numeric"
              pattern="[0-9]{4,6}"
              maxLength={6}
              className={styles.input}
              value={pin}
              onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))}
              autoFocus
              required
            />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="confirmPin">Confirm PIN</label>
            <input
              id="confirmPin"
              type="password"
              inputMode="numeric"
              pattern="[0-9]{4,6}"
              maxLength={6}
              className={styles.input}
              value={confirmPin}
              onChange={(e) => setConfirmPin(e.target.value.replace(/\D/g, ""))}
              required
            />
          </div>
          <button className={styles.submit} type="submit" disabled={loading || pin.length < 4}>
            {loading ? "Setting PIN…" : "Set PIN & continue"}
          </button>
        </form>
      </div>
    </main>
  );
}
