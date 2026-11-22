// PLT-054: pure rules of the in-app data safety training (Help panel for clinic staff, /platform/training for Nabd operators).
// The text is the help catalogue `training.*` (en + hi); Core only records a pass (POST /training/completions { moduleId, score },
// TrainingRules: module allow-list, pass mark 80, a pass is current for one year). React-free so it is unit-tested.
export const TRAINING_MODULE = "phi-handling";
export const PASS_MARK = 80;

/** The parts in reading order: `training.sections.<id>.title` and `.p1` to `.p4` (a part may have fewer points). */
export const TRAINING_SECTIONS = ["why", "phishing", "handling", "whatsapp", "devices", "incident"] as const;
export const SECTION_POINTS = ["p1", "p2", "p3", "p4"] as const;

/** The questions: `training.quiz.<id>.q` with options `.a`, `.b`, `.c`; `correct` is the right option. */
export const QUIZ_OPTIONS = ["a", "b", "c"] as const;
export type QuizOption = (typeof QUIZ_OPTIONS)[number];
export const TRAINING_QUIZ = [
  { id: "q1", correct: "b" }, { id: "q2", correct: "b" }, { id: "q3", correct: "c" }, { id: "q4", correct: "b" }, { id: "q5", correct: "b" },
] as const satisfies readonly { id: string; correct: QuizOption }[];
export type Answers = Partial<Record<string, QuizOption>>;

export const quizComplete = (a: Answers): boolean => TRAINING_QUIZ.every((q) => !!a[q.id]);
/** Right answers as a whole percentage (4 of 5 = 80, the pass mark). */
export const quizScore = (a: Answers): number => Math.round((TRAINING_QUIZ.filter((q) => a[q.id] === q.correct).length * 100) / TRAINING_QUIZ.length);
export const quizPassed = (score: number): boolean => score >= PASS_MARK;

export type TrainingState = "current" | "due" | "overdue";
const STATES: readonly string[] = ["current", "due", "overdue"];
export type TrainingStatus = { state: TrainingState; completedAt: string | null; dueAt: string | null };
const str = (v: unknown): string | null => (typeof v === "string" ? v : null);

/** The answer of GET .../training/completions/me if it has the expected shape, else undefined (read as a failed load). */
export function asTrainingStatus(body: unknown): TrainingStatus | undefined {
  if (!body || typeof body !== "object" || Array.isArray(body)) return undefined;
  const b = body as Record<string, unknown>;
  if (typeof b.state !== "string" || !STATES.includes(b.state)) return undefined;
  return { state: b.state as TrainingState, completedAt: str(b.completedAt), dueAt: str(b.dueAt) };
}

export type TrainingRosterEntry = TrainingStatus & { id: string; name: string; role: string; invited: boolean };
export type TrainingRoster = { total: number; current: number; entries: TrainingRosterEntry[] };

/** GET /training/completions (clinic team, rows under `staff`) or /platform/training/completions (Nabd team, `operators`). */
export function asTrainingRoster(body: unknown): TrainingRoster | undefined {
  if (!body || typeof body !== "object" || Array.isArray(body)) return undefined;
  const b = body as Record<string, unknown>;
  const rows = Array.isArray(b.staff) ? b.staff : Array.isArray(b.operators) ? b.operators : undefined;
  if (!rows || typeof b.total !== "number" || typeof b.current !== "number") return undefined;
  const entries: TrainingRosterEntry[] = [];
  for (const r of rows as Record<string, unknown>[]) {
    const s = asTrainingStatus(r);
    const id = str(r?.staffId) ?? str(r?.operatorId);
    if (!s || !id || typeof r.name !== "string") return undefined;
    entries.push({ ...s, id, name: r.name, role: str(r.role) ?? "", invited: r.accountStatus === "invited" });
  }
  return { total: b.total, current: b.current, entries };
}
