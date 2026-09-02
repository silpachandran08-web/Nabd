"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import styles from "../../login/login.module.css";

// Matches POST /v1/owners/auth/login (OwnerController) — a separate top-level identity from any
// one clinic's staff login (NB-350): this PIN is the owner's own, not tied to a tenant slug, and
// only ever unlocks a workspace list, never clinic data directly.
type PendingWorkspaceToken = { pendingToken: string; expiresIn: number };
type Problem = { title: string; detail: string };

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

export default function OwnerLoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [pin, setPin] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/owners/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, pin }),
      });
      const body = await res.json();
      if (!res.ok) {
        setFormError((body as Problem).detail || "Email or PIN is incorrect.");
        return;
      }
      const pair = body as PendingWorkspaceToken;
      sessionStorage.setItem("nabd_owner_pending_token", pair.pendingToken);
      router.replace("/owner/workspaces");
    } catch {
      setFormError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <h1 className={styles.title}>Sign in as owner</h1>
        <p className={styles.subtitle}>One login for every clinic you own — pick a workspace next.</p>
        <form onSubmit={submit} noValidate>
          {formError && <div className={styles.formError} role="alert">{formError}</div>}
          <div className={styles.field}>
            <label className={styles.label} htmlFor="email">Email</label>
            <input
              id="email"
              type="email"
              className={styles.input}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="pin">PIN</label>
            <input
              id="pin"
              type="password"
              inputMode="numeric"
              pattern="[0-9]{4,6}"
              maxLength={6}
              className={styles.input}
              value={pin}
              onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))}
              required
            />
          </div>
          <button className={styles.submit} type="submit" disabled={loading}>
            {loading ? "Signing in…" : "Sign in"}
          </button>
        </form>
        <p className={styles.hint}>This is your owner account, separate from any one clinic&apos;s staff login.</p>
      </div>
    </main>
  );
}
