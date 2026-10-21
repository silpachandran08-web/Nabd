// ACC-015: pure rules for the doctor's session controls on My Day. React-free so they are unit-tested.
export type SessionState = "in" | "paused" | "out";
export type SessionAction = "start" | "pause" | "resume" | "end";
export const EXTEND_MINUTES = [5, 10, 15] as const;

/** What a doctor can tap in each state; anything else is absent, not disabled. */
export function sessionActions(state: SessionState): { action: SessionAction; label: string }[] {
  if (state === "out") return [{ action: "start", label: "Start session" }];
  if (state === "in") return [{ action: "pause", label: "Pause" }, { action: "end", label: "End session" }];
  return [{ action: "resume", label: "Resume" }, { action: "end", label: "End session" }];
}

export const sessionLabel = (state: SessionState): string => (state === "in" ? "Doctor in" : state === "paused" ? "Paused" : "Out");

/** Error line for a failed session/extend call: the server's detail, else a fallback. */
export async function problemMessage(res: Response, fallback: string): Promise<string> {
  const p = await res.json().catch(() => null);
  return p?.detail ?? p?.title ?? fallback;
}
