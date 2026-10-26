// FEF-030: the consult workspace's step rail and the panels that open on top of it, as one reducer (pure, unit-tested).
// Deep links: ?entry=<id>&step=<reason|examine|assess|plan|close>.
export type Step = "reason" | "examine" | "assess" | "plan" | "close";
export const STEP_IDS: readonly Step[] = ["reason", "examine", "assess", "plan", "close"];
export const isStep = (v: string | null): v is Step => STEP_IDS.some((s) => s === v);

export type WorkflowState = { step: Step; closeAck: boolean; showAmend: boolean; showDental: boolean; showAllergies: boolean };
export type WorkflowAction =
  | { type: "goto"; step: Step }
  | { type: "closeAck"; value: boolean }
  | { type: "amend"; open: boolean }
  | { type: "dental"; open: boolean }
  | { type: "allergies"; open: boolean };

export const initialWorkflow = (step: Step = "reason"): WorkflowState => ({ step, closeAck: false, showAmend: false, showDental: false, showAllergies: false });

export function workflowReducer(s: WorkflowState, a: WorkflowAction): WorkflowState {
  switch (a.type) {
    case "goto": return s.step === a.step ? s : { ...s, step: a.step };
    case "closeAck": return { ...s, closeAck: a.value };
    case "amend": return { ...s, showAmend: a.open };
    case "dental": return { ...s, showDental: a.open };
    case "allergies": return { ...s, showAllergies: a.open };
  }
}
