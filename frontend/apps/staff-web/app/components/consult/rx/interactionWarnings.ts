// CLN-032: pure view model of the drug-drug interaction (ddi) and duplicate-therapy (duplicate) warnings of one
// prescription line. No clinical content lives here: severity, the other medicine, mechanism, advice and source all come
// from the server (the licensed knowledge base). The one rule this file owns: it only ever describes a warning the server
// sent, or says that a line was NOT checked. It has no "nothing found" state, because an unchecked line and a clean line
// must never look alike.
import type { PrescriptionItem, RxWarning } from "../shared";

export const isInteractionWarning = (w: { kind: string }): boolean => w.kind === "ddi" || w.kind === "duplicate";

/** The warnings the generic card list (RxWarnings) shows: every kind except ddi and duplicate. */
export const genericWarnings = (item: { warnings?: RxWarning[] }): RxWarning[] => (item.warnings ?? []).filter((w) => !isInteractionWarning(w));

/** A decision on a ddi / duplicate warning names the warning (a line can carry several); other kinds stay per kind. */
export const decisionMatch = (w: { kind: string; matched: string }): string | undefined => (isInteractionWarning(w) ? w.matched : undefined);

const SEVERITY_RANK: Record<string, number> = { contraindicated: 0, major: 1, moderate: 2, minor: 3 };

export type InteractionCard = {
  key: string;
  warning: RxWarning;
  /** danger: needs a decision; info: decision recorded; warn: advisory (never blocks signing). */
  tone: "danger" | "info" | "warn";
  open: boolean;
  advisory: boolean;
  /** Glossary id of the severity, or null for a value this screen does not know (then the server's word is shown as sent). */
  severityId: string | null;
  /** Where the other medicine is: another line of this prescription, a running prescription, or patient-reported. */
  other: "draft" | "rx" | "reported";
  /** 1-based line number of the other medicine in this prescription (draft only). */
  otherLine: number | null;
  hasWhy: boolean;
};

/** One card per ddi / duplicate warning of the line, most severe first; open ones before decided ones of the same severity. */
export function interactionCards(item: { warnings?: RxWarning[] }): InteractionCard[] {
  return (item.warnings ?? [])
    .filter(isInteractionWarning)
    .map((w): InteractionCard => {
      const open = w.requiresDecision && !w.resolved;
      return {
        key: `${w.kind}:${w.matched}`,
        warning: w,
        tone: open ? "danger" : w.requiresDecision ? "info" : "warn",
        open,
        advisory: !w.requiresDecision,
        severityId: w.severity in SEVERITY_RANK ? `interaction.severity.${w.severity}` : null,
        other: w.otherFrom === "rx" || w.otherFrom === "reported" ? w.otherFrom : "draft",
        otherLine: typeof w.otherLineRef === "number" ? w.otherLineRef + 1 : null,
        hasWhy: Boolean(w.mechanism || w.management || w.source || w.sourceRef),
      };
    })
    .sort((a, b) => (SEVERITY_RANK[a.warning.severity] ?? 9) - (SEVERITY_RANK[b.warning.severity] ?? 9) || Number(b.open) - Number(a.open));
}

/** True only when the server said a knowledge base is active AND this line, as saved, could not be checked (free text).
 * No knowledge base (null / absent) shows nothing at all; a line edited since the last save (its warnings are dropped
 * from view, see useRxPad.patchRxItem) shows nothing either until the server has answered for the new text. */
export const lineNotChecked = (item: Pick<PrescriptionItem, "interactionCheck" | "warnings">): boolean =>
  item.interactionCheck === "not_checked" && item.warnings !== undefined;
