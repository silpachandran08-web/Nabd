"use client";

import { useState, type FormEvent } from "react";
import { useParams, useRouter } from "next/navigation";
import { decodeAccessToken, landingPathFor } from "../../lib/session";
import { storeTokens } from "../../lib/apiClient";
import styles from "../../login/login.module.css";
import MfaEnrolCard from "../../components/MfaEnrolCard";

// Matches POST /v1/staff/invitations/{token}/accept (StaffController.acceptInvite) — used by both
// regular staff invites (staff/page.tsx) and the owner invite provisioning now sends (NB-353).
type TokenPair = { accessToken: string; refreshToken: string; expiresIn: number };
type Problem = { title: string; detail: string };

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

export default function AcceptInvitePage() {
  const router = useRouter();
  const params = useParams<{ token: string }>();
  const token = params.token;

  const [pin, setPin] = useState("");
  const [confirmPin, setConfirmPin] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  // PLT-005: a role that requires MFA gets a setup token instead of a session; enrol here, then sign in.
  const [setupToken, setSetupToken] = useState<string | null>(null);
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    if (pin !== confirmPin) {
      setFormError("PINs don't match.");
      return;
    }
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/staff/invitations/${token}/accept`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ pin }),
      });
      const body = await res.json();
      if (!res.ok) {
        setFormError((body as Problem).detail || "That invite link is invalid or has expired.");
        return;
      }
      if ("setupToken" in body) {
        setSetupToken(body.setupToken);
        return;
      }
      const pair = body as TokenPair;
      storeTokens(pair);
      // Land where the role works (as login does); /account when the role has no home worklist.
      const claims = decodeAccessToken(pair.accessToken);
      router.replace(landingPathFor(claims?.permissions ?? [], claims?.owner === true));
    } catch {
      setFormError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }

  if (setupToken) {
    return (
      <main className={styles.page}>
        <div className={styles.card}>
          {recoveryCodes ? (
            <>
              <h1 className={styles.title}>Save your recovery codes</h1>
              <p className={styles.subtitle}>Each code works once if you lose your authenticator. They won&apos;t be shown again.</p>
              <div className={styles.recoveryCodes}>{recoveryCodes.map((c) => <span key={c}>{c}</span>)}</div>
              <button className={styles.submit} type="button" onClick={() => router.replace("/login")}>Sign in now</button>
            </>
          ) : (
            <MfaEnrolCard enrollUrl={`${API_BASE}/auth/mfa/enroll`} confirmUrl={`${API_BASE}/auth/mfa/confirm`} setupToken={setupToken}
              subtitle="Your role requires an authenticator app before you can sign in."
              onDone={(b) => setRecoveryCodes((b as { recoveryCodes?: string[] } | null)?.recoveryCodes ?? [])} />
          )}
        </div>
      </main>
    );
  }

  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <h1 className={styles.title}>Set your PIN</h1>
        <p className={styles.subtitle}>Choose the PIN you&apos;ll use to sign in from now on. You can change it later.</p>
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
            {loading ? "Setting PIN…" : "Set PIN & sign in"}
          </button>
        </form>
      </div>
    </main>
  );
}
