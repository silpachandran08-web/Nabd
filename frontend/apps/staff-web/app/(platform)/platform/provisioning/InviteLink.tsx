"use client";

import { useState } from "react";
import Icon from "../../../components/ui/Icon";
import styles from "./provisioning.module.css";

// Reveal-once invite link (NB-353/354). The token lives only in the parent's in-memory state — it
// is never written to storage and is gone on refresh. Masked by default so it isn't left on screen
// during a screen-share; copy works without revealing. The API returns no expiry field, so the
// expiry line is deliberately generic rather than a made-up duration.
export default function InviteLink({ label, path, token, emailed = false }: { label: string; path: string; token: string; emailed?: boolean }) {
  const [revealed, setRevealed] = useState(false);
  const [copied, setCopied] = useState<"ok" | "failed" | null>(null);
  const origin = typeof window !== "undefined" ? window.location.origin : "";
  const full = `${origin}${path}${token}`;

  async function copy() {
    try {
      await navigator.clipboard.writeText(full);
      setCopied("ok");
    } catch {
      setCopied("failed");
    }
    setTimeout(() => setCopied(null), 2500);
  }

  return (
    <div className={styles.cNoteWarn}>
      <div className={styles.inviteHead}>{label}</div>
      <div className={styles.inviteRow}>
        <code className={styles.inviteCode}>{revealed ? full : `${origin}${path}${"•".repeat(12)}`}</code>
        <button type="button" className={styles.actionBtn} aria-pressed={revealed} onClick={() => setRevealed((v) => !v)}>
          {revealed ? "Hide" : "Reveal"}
        </button>
        <button type="button" className={styles.actionBtn} onClick={copy}>
          <Icon name={copied === "ok" ? "check" : "list"} size={12} /> {copied === "ok" ? "Copied" : copied === "failed" ? "Copy failed" : "Copy"}
        </button>
      </div>
      <div className={styles.inviteMeta}>
        Shown once, kept only in this tab — it disappears when you leave or refresh this page, and the link stops working once it expires or is used. {emailed ? "Also emailed to the owner (the link expires in 72 hours); relay by hand if it doesn't arrive." : "This link is not emailed — relay it to the owner by hand."}
      </div>
    </div>
  );
}
