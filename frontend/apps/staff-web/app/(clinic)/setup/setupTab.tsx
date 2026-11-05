"use client";

// FEF-032: what every Clinic Setup tab route shares (moved unchanged from the old single setup page): the
// per-tab load (each tab loads only its own data, so one tab's missing grant or failing endpoint can't blank the others),
// the save helper with its Retry banner, and the loading / error frame around a tab's content. Loaded lists and the
// failed save live in the setup store (setupStore.ts), so they survive tab switches as on the old page.
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { useSetupState } from "./setupStore";
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
  // One record for every tab, as the old page's state: Charges and Policies share `policies`, Shifts and Licences `staff`.
  const [shared, setShared] = useSetupState<Record<string, unknown>>("data", {});
  const data = Object.fromEntries(Object.entries(initial).map(([k, v]) => [k, k in shared ? shared[k] : v])) as T;
  const setData = useCallback((patch: Partial<T>) => setShared((prev) => ({ ...prev, ...patch })), [setShared]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // A failed save keeps the form as it is, and a banner (in the setup layout, on every tab) offers Retry (same body, same Idempotency-Key).
  const [saveFail, setSaveFail] = useSetupState<SaveFail | null>("saveFail", null);

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
      setData(got);
    } catch (e) {
      setError(reasonText(e, ""));
    } finally {
      setLoading(false);
    }
  }, [setData]);

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

type SaveFail = { message: string; retry: () => void };
type TabState = { loading: boolean; error: string | null; loadAll: () => void };

/** The last failed save with Retry; shown by the setup layout above whichever tab is open, until a save succeeds. */
export function SaveFailBanner() {
  const [saveFail] = useSetupState<SaveFail | null>("saveFail", null);
  if (!saveFail) return null;
  return (
    <div className={styles.errorState} role="alert">
      {saveFail.message}{" "}
      <button type="button" className={styles.wizardBtn} onClick={saveFail.retry}>Retry</button>
    </div>
  );
}

/** The tab's skeleton, error state or content. */
export function SetupTabBody({ tab, children }: { tab: TabState; children: ReactNode }) {
  return tab.loading ? <div className={styles.card}><Skeleton rows={5} label="Loading" /></div>
    : tab.error ? <div className={styles.card}><ErrorState message={tab.error} onRetry={tab.loadAll} /></div>
    : <>{children}</>;
}
