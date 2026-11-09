"use client";

// FEF-032: the views of the staff sign-in screen, moved unchanged from the old single page. They only render the
// reducer state (loginMachine.ts) and dispatch input; the requests stay in page.tsx.
import type { Dispatch, FormEvent } from "react";
import Link from "next/link";
import { useTranslations } from "next-intl";
import styles from "./login.module.css";
import type { Field, LoginAction, LoginState } from "./loginMachine";

type ViewProps = { s: LoginState; dispatch: Dispatch<LoginAction> };
const fieldOf = (dispatch: Dispatch<LoginAction>, name: Field) => (value: string) => dispatch({ type: "field", name, value });
const digits = (v: string) => v.replace(/\D/g, "");

export function PinChangeView({ s, dispatch, onSubmit }: ViewProps & { onSubmit: (e: FormEvent) => void }) {
  const t = useTranslations("login");
  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <h1 className={styles.title}>{t("pinChange.title")}</h1>
        <p className={styles.subtitle}>{t("pinChange.subtitle")}</p>
        {s.formError && <div className={styles.formError} role="alert">{s.formError}</div>}
        <form onSubmit={onSubmit} noValidate>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="cNewPin">{t("pinChange.newPin")}</label>
            <input id="cNewPin" type="password" inputMode="numeric" pattern="[0-9]{6,8}" maxLength={8} className={styles.input}
              value={s.newPin} onChange={(e) => fieldOf(dispatch, "newPin")(digits(e.target.value))} autoFocus required />
          </div>
          <button className={styles.submit} type="submit" disabled={s.loading}>{s.loading ? t("pinChange.saving") : t("pinChange.save")}</button>
        </form>
      </div>
    </main>
  );
}

export function MfaSetupView({ s, dispatch, onSubmit }: ViewProps & { onSubmit: (e: FormEvent) => void }) {
  const t = useTranslations("login");
  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <h1 className={styles.title}>{t("mfa.setupTitle")}</h1>
        <p className={styles.subtitle}>{t("mfa.setupRole")}</p>
        {s.formError && <div className={styles.formError} role="alert">{s.formError}</div>}
        {s.view === "mfaSetup" && (
          <form onSubmit={onSubmit} noValidate>
            <p className={styles.subtitle}>{t("mfa.setupSecret")}</p>
            <div className={styles.secretBox}>{s.secretBase32}</div>
            <a className={styles.hint} href={s.otpauthUri}>{t("mfa.openApp")}</a>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="setupCode">{t("mfa.code")}</label>
              <input
                id="setupCode"
                className={styles.input}
                inputMode="numeric"
                pattern="[0-9]{6}"
                maxLength={6}
                autoFocus
                value={s.code}
                onChange={(e) => fieldOf(dispatch, "code")(digits(e.target.value))}
                required
              />
            </div>
            <button className={styles.submit} type="submit" disabled={s.loading || s.code.length !== 6}>
              {s.loading ? t("mfa.confirming") : t("mfa.confirm")}
            </button>
          </form>
        )}
        {s.view === "mfaSetupDone" && (
          <>
            <p className={styles.subtitle}>{t("mfa.recoverySave")}</p>
            <div className={styles.recoveryCodes}>
              {s.recoveryCodes.map((c) => <span key={c}>{c}</span>)}
            </div>
            <button className={styles.submit} type="button" onClick={() => dispatch({ type: "back" })}>{t("mfa.signInNow")}</button>
          </>
        )}
      </div>
    </main>
  );
}

export function MfaVerifyView({ s, dispatch, onSubmit }: ViewProps & { onSubmit: (e: FormEvent) => void }) {
  const t = useTranslations("login");
  const { useRecovery, code } = s;
  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <h1 className={styles.title}>{t("mfa.verifyTitle")}</h1>
        <p className={styles.subtitle}>{useRecovery ? t("mfa.verifyRecoveryHint") : t("mfa.verifyHint")}</p>
        <form onSubmit={onSubmit} noValidate>
          {s.formError && <div className={styles.formError} role="alert">{s.formError}</div>}
          <div className={styles.field}>
            <label className={styles.label} htmlFor="code">{useRecovery ? t("mfa.recoveryCode") : t("mfa.code")}</label>
            <input
              id="code"
              className={styles.input}
              inputMode={useRecovery ? "text" : "numeric"}
              pattern={useRecovery ? undefined : "[0-9]{6}"}
              maxLength={useRecovery ? 32 : 6}
              autoFocus
              autoComplete="off"
              value={code}
              onChange={(e) => fieldOf(dispatch, "code")(useRecovery ? e.target.value : digits(e.target.value))}
              required
            />
          </div>
          <button className={styles.submit} type="submit" disabled={s.loading || (useRecovery ? code.trim().length < 6 : code.length !== 6)}>
            {s.loading ? t("mfa.verifying") : t("mfa.verify")}
          </button>
          <button type="button" className={`${styles.submit} ${styles.altSubmit}`} onClick={() => dispatch({ type: "toggleRecovery" })}>
            {useRecovery ? t("mfa.useAuthenticator") : t("mfa.useRecovery")}
          </button>
        </form>
      </div>
    </main>
  );
}

export function ForgotView({ s, dispatch, onRequest, onConfirm }: ViewProps & { onRequest: (e: FormEvent) => void; onConfirm: (e: FormEvent) => void }) {
  const t = useTranslations("login");
  const back = () => dispatch({ type: "back" });
  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <h1 className={styles.title}>{t("forgot.title")}</h1>
        {s.formError && <div className={styles.formError} role="alert">{s.formError}</div>}
        {s.view === "forgotRequest" && (
          <form onSubmit={onRequest} noValidate>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="fTenant">{t("forgot.clinicCode")}</label>
              <input id="fTenant" className={styles.input} value={s.tenantSlug} onChange={(e) => fieldOf(dispatch, "tenantSlug")(e.target.value)} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="fMobile">{t("forgot.mobile")}</label>
              <input id="fMobile" className={styles.input} value={s.mobilePhone} onChange={(e) => fieldOf(dispatch, "mobilePhone")(e.target.value)} required />
            </div>
            <button className={styles.submit} type="submit" disabled={s.loading}>{s.loading ? t("forgot.sending") : t("forgot.send")}</button>
          </form>
        )}
        {s.view === "forgotConfirm" && (
          <form onSubmit={onConfirm} noValidate>
            <p className={styles.subtitle}>{t("forgot.sent")}</p>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="fToken">{t("forgot.resetCode")}</label>
              <input id="fToken" className={styles.input} value={s.resetToken} onChange={(e) => fieldOf(dispatch, "resetToken")(e.target.value)} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="fNewPin">{t("forgot.newPin")}</label>
              <input id="fNewPin" type="password" inputMode="numeric" pattern="[0-9]{6,8}" maxLength={8} className={styles.input}
                value={s.newPin} onChange={(e) => fieldOf(dispatch, "newPin")(digits(e.target.value))} required />
            </div>
            <button className={styles.submit} type="submit" disabled={s.loading}>{s.loading ? t("forgot.saving") : t("forgot.setPin")}</button>
          </form>
        )}
        {s.view === "forgotDone" && (
          <>
            <p className={styles.success}>{t("forgot.done")}</p>
            <button className={styles.submit} type="button" onClick={back}>{t("forgot.back")}</button>
          </>
        )}
        <button className={styles.linkBtn} type="button" onClick={back}>{t("forgot.back")}</button>
      </div>
    </main>
  );
}

export function OtpView({ s, dispatch, onRequest, onVerify }: ViewProps & { onRequest: (e: FormEvent) => void; onVerify: (e: FormEvent) => void }) {
  const t = useTranslations("login");
  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <h1 className={styles.title}>{t("otp.title")}</h1>
        {s.formError && <div className={styles.formError} role="alert">{s.formError}</div>}
        {s.view === "otpRequest" && (
          <form onSubmit={onRequest} noValidate>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="oTenant">{t("otp.clinicCode")}</label>
              <input id="oTenant" className={styles.input} value={s.tenantSlug} onChange={(e) => fieldOf(dispatch, "tenantSlug")(e.target.value)} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="oMobile">{t("otp.mobile")}</label>
              <input id="oMobile" className={styles.input} value={s.mobilePhone} onChange={(e) => fieldOf(dispatch, "mobilePhone")(e.target.value)} required />
            </div>
            <button className={styles.submit} type="submit" disabled={s.loading}>{s.loading ? t("otp.sending") : t("otp.send")}</button>
          </form>
        )}
        {s.view === "otpVerify" && (
          <form onSubmit={onVerify} noValidate>
            <p className={styles.subtitle}>{t("otp.sent")}</p>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="oCode">{t("otp.code")}</label>
              <input id="oCode" className={styles.input} inputMode="numeric" pattern="[0-9]{6}" maxLength={6} autoFocus
                value={s.code} onChange={(e) => fieldOf(dispatch, "code")(digits(e.target.value))} required />
            </div>
            <button className={styles.submit} type="submit" disabled={s.loading || s.code.length !== 6}>{s.loading ? t("otp.verifying") : t("otp.verify")}</button>
          </form>
        )}
        <button className={styles.linkBtn} type="button" onClick={() => dispatch({ type: "back" })}>{t("forgot.back")}</button>
      </div>
    </main>
  );
}

export function SignInView({ s, dispatch, onSubmit, onRetry, signedOut, accessChanged }: ViewProps & {
  onSubmit: (e: FormEvent) => void; onRetry: () => void; signedOut: boolean; accessChanged: boolean;
}) {
  const t = useTranslations("login");
  const { fieldErrors, formError } = s;
  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <h1 className={styles.title}>{t("signIn.title")}</h1>
        <p className={styles.subtitle}>{t("signIn.subtitle")}</p>
        <form onSubmit={onSubmit} noValidate>
          {(signedOut || accessChanged) && !formError && <div className={styles.formError} role="status">{accessChanged ? t("signIn.accessChanged") : t("signIn.signedOut")}</div>}
          {formError && <div className={styles.formError} role="alert">{formError}</div>}
          {s.loading && s.slow && <p className={styles.hint} role="status">{t("signIn.slow")}</p>}
          {s.timedOut && !s.loading && <button className={`${styles.linkBtn} ${styles.retryBtn}`} type="button" onClick={onRetry}>{t("signIn.retry")}</button>}

          <div className={styles.field}>
            <label className={styles.label} htmlFor="tenantSlug">{t("signIn.clinicCode")}</label>
            <input
              id="tenantSlug"
              className={`${styles.input} ${fieldErrors.tenantSlug ? styles.inputError : ""}`}
              value={s.tenantSlug}
              onChange={(e) => fieldOf(dispatch, "tenantSlug")(e.target.value)}
              aria-invalid={!!fieldErrors.tenantSlug}
              required
            />
            {fieldErrors.tenantSlug && <p className={styles.fieldError}>{fieldErrors.tenantSlug}</p>}
          </div>

          <div className={styles.field}>
            <label className={styles.label} htmlFor="email">{t("signIn.email")}</label>
            <input
              id="email"
              type="email"
              className={`${styles.input} ${fieldErrors.email ? styles.inputError : ""}`}
              value={s.email}
              onChange={(e) => fieldOf(dispatch, "email")(e.target.value)}
              aria-invalid={!!fieldErrors.email}
              required
            />
            {fieldErrors.email && <p className={styles.fieldError}>{fieldErrors.email}</p>}
          </div>

          <div className={styles.field}>
            <label className={styles.label} htmlFor="pin">{t("signIn.pin")}</label>
            <input
              id="pin"
              type="password"
              inputMode="numeric"
              pattern="[0-9]{4,8}"
              maxLength={8}
              className={`${styles.input} ${fieldErrors.pin ? styles.inputError : ""}`}
              value={s.pin}
              onChange={(e) => fieldOf(dispatch, "pin")(digits(e.target.value))}
              aria-invalid={!!fieldErrors.pin}
              required
            />
            {fieldErrors.pin && <p className={styles.fieldError}>{fieldErrors.pin}</p>}
          </div>

          <button className={styles.submit} type="submit" disabled={s.loading}>
            {s.loading ? t("signIn.submitting") : t("signIn.submit")}
          </button>
        </form>
        <button className={styles.linkBtn} type="button" onClick={() => dispatch({ type: "open", view: "forgotRequest" })}>
          {t("signIn.forgot")}
        </button>
        <button className={styles.linkBtn} type="button" onClick={() => dispatch({ type: "open", view: "otpRequest" })}>
          {t("signIn.otpInstead")}
        </button>
        <Link className={styles.linkBtn} href="/owner/login">{t("signIn.ownerLink")}</Link>
      </div>
    </main>
  );
}
