"use client";
// PLT-023: top-bar branch switcher for a person linked to more than one branch of their brand (GET /auth/branches lists
// only the branches they can enter now; one entry = no switcher at all). Choosing a branch saves open drafts, asks for that
// branch's own session (POST /auth/branches/select: one tenant per token), drops every cached answer of the branch left and
// reloads into the new one. The owner's own switcher is FacilitySwitcher (sidebar). Each branch keeps one colour chip.
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { useQueryClient } from "@tanstack/react-query";
import styles from "./shell.module.css";
import { api, ApiError } from "../lib/api";
import { storeTokens } from "../lib/apiClient";
import { clearIntents } from "../lib/idempotency";
import { clearDrugIndex } from "../lib/drugIndex";
import { branchColor, flushOpenDrafts, showSwitcher, switchBranch, type BranchList } from "../lib/branches";
import { can } from "../lib/permissions";
import { decodeAccessToken, isOwner, landingPathFor } from "../lib/session";

type Pair = { accessToken: string; refreshToken: string; expiresIn: number };

export default function BranchSwitcher() {
  const t = useTranslations("shell.branch");
  const qc = useQueryClient();
  const [list, setList] = useState<BranchList | null>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const owner = isOwner();

  useEffect(() => {
    if (owner) return; // the owner switches in the sidebar; no request for a list that screen does not show
    let live = true;
    api.get<BranchList>("/auth/branches", { keepSession: true }).then((l) => { if (live) setList(l); }).catch(() => { /* no list, no switcher; never retried */ });
    return () => { live = false; };
  }, [owner]);

  useEffect(() => {
    if (!open) return;
    const k = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("keydown", k);
    return () => document.removeEventListener("keydown", k);
  }, [open]);

  if (owner || !list || !showSwitcher(list)) return null;
  const current = list.branches.find((b) => b.tenantId === list.currentTenantId);
  const others = list.branches.filter((b) => b.tenantId !== list.currentTenantId);
  const canReports = can("reports:view") && !list.reportsOnly;

  async function go(tenantId: string, reportsOnly: boolean) {
    setBusy(reportsOnly ? "reports" : tenantId);
    setError(null);
    try {
      await switchBranch<Pair>({
        flush: () => flushOpenDrafts(),
        select: (id, ro) => api.post<Pair>("/auth/branches/select", { tenantId: id, reportsOnly: ro }, { keepSession: true }),
        store: storeTokens,
        clearCaches: async () => { qc.clear(); clearIntents(); await clearDrugIndex(); },
        reload: () => {
          const claims = decodeAccessToken(localStorage.getItem("nabd_access_token") ?? "");
          window.location.assign(landingPathFor(claims?.permissions ?? [], claims?.owner === true));
        },
      }, tenantId, reportsOnly);
    } catch (e) {
      setError(e instanceof ApiError && e.detail ? e.detail : t("failed")); // the session you had is untouched
      setBusy(null);
    }
  }

  const label = list.reportsOnly ? t("allReports") : current?.name ?? "";
  return (
    <div className={styles.wrap}>
      <button type="button" className={styles.chip} aria-haspopup="menu" aria-expanded={open} aria-label={t("chip", { name: label })} onClick={() => setOpen((v) => !v)}>
        <span aria-hidden="true" style={{ inlineSize: 10, blockSize: 10, borderRadius: "50%", background: list.reportsOnly ? "var(--nb-text-secondary)" : branchColor(list.currentTenantId) }} />
        <bdi>{label}</bdi>
      </button>
      {open && (
        <>
          <div className={styles.backdrop} onClick={() => setOpen(false)} />
          <div className={styles.menu} role="menu" aria-label={t("menu")}>
            {others.map((b) => (
              <button key={b.tenantId} type="button" role="menuitem" className={styles.menuItem} disabled={busy !== null} onClick={() => void go(b.tenantId, false)}>
                <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
                  <span aria-hidden="true" style={{ inlineSize: 10, blockSize: 10, borderRadius: "50%", background: branchColor(b.tenantId) }} />
                  <bdi>{b.name}</bdi>
                </span>
                {busy === b.tenantId && <span role="status">{t("switching")}</span>}
              </button>
            ))}
            {canReports && (
              <button type="button" role="menuitem" className={styles.menuItem} disabled={busy !== null} onClick={() => void go(list.currentTenantId, true)}>
                {t("allReports")}{busy === "reports" && <span role="status">{t("switching")}</span>}
              </button>
            )}
            {error && <div role="alert">{error}</div>}
          </div>
        </>
      )}
    </div>
  );
}
