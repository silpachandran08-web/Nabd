"use client";

// FEF-032: what every Clinic Setup tab route shares (moved unchanged from the old single setup page): the
// per-tab load (each tab loads only its own data, so one tab's missing grant or failing endpoint can't blank the others),
// the save helper with its Retry banner, and the loading / error frame around a tab's content.
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import styles from "./setup.module.css";
import { ErrorState, Skeleton } from "../../components/ui/StateViews";
import { api, ApiError, reasonText } from "../../lib/api";
import { intentKeys } from "@nabd/api-client";

// FEF-018: one idempotency key per save intent, so a retry after a dropped answer cannot double-apply.
export const intents = intentKeys();

export type Charge = { id: string; code: string; name: string; category: string; baseAmount: number; followUpAmount: number | null; emergencyAmount: number | null; taxCode: string | null; taxRatePercent: number; doctorOverride: boolean; active: boolean; effectiveFrom: string; effectiveTo: string | null; displayOrder: number };
export type Policy = { id: string; policyKey: string; value: string; version: number };
export type StaffSummary = { id: string; name: string; roleName: string };

/** Loads this tab's endpoints (key -> GET path). A refused read shows the error state; a value only replaces what is held when it arrived. */
export function useSetupTab<T extends Record<string, unknown>>(paths: { [K in keyof T]: string }, initial: T) {
  const tabPaths = useRef(paths);
  const [data, setData] = useState<T>(initial);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // A failed save keeps the form as it is: the tab stays, and a banner offers Retry (same body, same Idempotency-Key).
  const [saveFail, setSaveFail] = useState<{ message: string; retry: () => void } | null>(null);

  const loadAll = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const fetchJson = async (path: string): Promise<unknown> => {
        try {
          return await api.get<unknown>(path);
        } catch (e) {
          if (!(e instanceof ApiError)) throw e;
          setError(reasonText(e, `Failed to load ${path}`));
          return null;
        }
      };
      const keys = Object.keys(tabPaths.current) as (keyof T)[];
      const values = await Promise.all(keys.map((k) => fetchJson(tabPaths.current[k])));
      const got = Object.fromEntries(keys.map((k, i) => [k, values[i]]).filter(([, v]) => v)) as Partial<T>;
      setData((prev) => ({ ...prev, ...got }));
    } catch (e) {
      setError(reasonText(e, ""));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // Deferred to a microtask so the effect body itself never calls setState
    // synchronously (react-hooks/set-state-in-effect) — loadAll does, immediately.
    void Promise.resolve().then(loadAll);
  }, [loadAll]);

  const mutate = async (method: "POST" | "PATCH" | "DELETE", path: string, body?: unknown): Promise<boolean> => {
    const name = `${method} ${path}`;
    try {
      await api.request(method, path, { body, idempotencyKey: intents.get(name, body) });
      intents.done(name);
      setSaveFail(null);
      return true;
    } catch (e) {
      setSaveFail({ message: reasonText(e, "Request failed"), retry: () => { void mutate(method, path, body).then((ok) => { if (ok) void loadAll(); }); } });
      return false;
    }
  };
  const post = (path: string, body: unknown) => mutate("POST", path, body);
  const patch = (path: string, body: unknown) => mutate("PATCH", path, body);

  return { data, setData, loading, error, loadAll, saveFail, mutate, post, patch };
}

type TabState = { loading: boolean; error: string | null; loadAll: () => void; saveFail: { message: string; retry: () => void } | null };

/** The Retry banner of a failed save, then the tab's skeleton, error state or content. */
export function SetupTabBody({ tab, children }: { tab: TabState; children: ReactNode }) {
  return (
    <>
      {tab.saveFail && (
        <div className={styles.errorState} role="alert">
          {tab.saveFail.message}{" "}
          <button type="button" className={styles.wizardBtn} onClick={tab.saveFail.retry}>Retry</button>
        </div>
      )}
      {tab.loading ? <div className={styles.card}><Skeleton rows={5} label="Loading" /></div>
        : tab.error ? <div className={styles.card}><ErrorState message={tab.error} onRetry={tab.loadAll} /></div>
        : children}
    </>
  );
}
