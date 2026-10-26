"use client";

import { useState, type FormEvent } from "react";
import { useParams, useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { decodeAccessToken, landingPathFor } from "../../lib/session";
import { storeTokens } from "../../lib/apiClient";
import styles from "../../login/login.module.css";
import MfaEnrolCard from "../../components/MfaEnrolCard";
import { publicApi, ApiError, reasonText } from "../../lib/api";

// Matches POST /v1/staff/invitations/{token}/accept (StaffController.acceptInvite) — used by both
// regular staff invites (staff/page.tsx) and the owner invite provisioning now sends (NB-353).
type TokenPair = { accessToken: string; refreshToken: string; expiresIn: number };

export default function AcceptInvitePage() {
  const router = useRouter();
  const t = useTranslations("login.invite");
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
      setFormError(t("mismatch"));
      return;
    }
    setLoading(true);
    try {
      const body = await publicApi.post<TokenPair | { setupToken: string }>(`/staff/invitations/${token}/accept`, { pin }, { keepSession: true });
      if ("setupToken" in body) {
        setSetupToken(body.setupToken);
        return;
      }
      storeTokens(body);
      // Land where the role works (as login does); /account when the role has no home worklist.
      const claims = decodeAccessToken(body.accessToken);
      router.replace(landingPathFor(claims?.permissions ?? [], claims?.owner === true));
    } catch (err) {
      setFormError(err instanceof ApiError ? err.detail || t("invalid") : reasonText(err, ""));
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
              <h1 className={styles.title}>{t("codesTitle")}</h1>
              <p className={styles.subtitle}>{t("codesHint")}</p>
              <div className={styles.recoveryCodes}>{recoveryCodes.map((c) => <span key={c}>{c}</span>)}</div>
              <button className={styles.submit} type="button" onClick={() => router.replace("/login")}>{t("signInNow")}</button>
            </>
          ) : (
            <MfaEnrolCard enrollPath="/auth/mfa/enroll" confirmPath="/auth/mfa/confirm" setupToken={setupToken}
              subtitle={t("mfaSubtitle")}
              onDone={(b) => setRecoveryCodes((b as { recoveryCodes?: string[] } | null)?.recoveryCodes ?? [])} />
          )}
        </div>
      </main>
    );
  }

  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <h1 className={styles.title}>{t("title")}</h1>
        <p className={styles.subtitle}>{t("subtitle")}</p>
        <form onSubmit={submit} noValidate>
          {formError && <div className={styles.formError} role="alert">{formError}</div>}
          <div className={styles.field}>
            <label className={styles.label} htmlFor="pin">{t("newPin")}</label>
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
            <label className={styles.label} htmlFor="confirmPin">{t("confirmPin")}</label>
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
            {loading ? t("submitting") : t("submit")}
          </button>
        </form>
      </div>
    </main>
  );
}
