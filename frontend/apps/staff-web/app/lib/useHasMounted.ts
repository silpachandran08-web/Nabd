import { useSyncExternalStore } from "react";

// True after hydration, false during SSR/first paint — for pages that read localStorage-backed
// permissions and must not render role-dependent UI on the server.
export function useHasMounted(): boolean {
  return useSyncExternalStore(() => () => {}, () => true, () => false);
}
