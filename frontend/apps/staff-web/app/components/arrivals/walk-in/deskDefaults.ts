// ACC-031 smart defaults: the doctor and payment mode used at the last check-in on this desk (browser storage only; a convenience).
// Ids are validated against the live roster by the caller, so a stale or foreign value is simply ignored.
const KEYS = { doctor: "nabd.desk.lastDoctor", payment: "nabd.desk.lastPayment" } as const;

export function readDeskDefault(which: keyof typeof KEYS): string {
  try { return window.localStorage.getItem(KEYS[which]) ?? ""; } catch { return ""; }
}
export function writeDeskDefault(which: keyof typeof KEYS, value: string): void {
  try { if (value) window.localStorage.setItem(KEYS[which], value); } catch { /* private window or blocked storage: no default next time */ }
}
