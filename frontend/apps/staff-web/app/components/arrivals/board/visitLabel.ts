// FEF-038: the Visit column label from the catalogue (visit type first, then the visit source).
import { VISIT_TYPE_LABEL } from "./boardLogic";

type Tr = { (key: string): string; has: (key: string) => boolean };

export function visitLabel(t: Tr, r: { visitType?: string | null; source: string }): string {
  const k = r.visitType && r.visitType in VISIT_TYPE_LABEL ? r.visitType : r.source;
  return t.has(`visit.${k}`) ? t(`visit.${k}`) : k;
}
