// Lint fixture (FEF-012): MUST fail. A feature importing another feature folder.
import { total } from "../billing/total";
import { useMyDay } from "../../../app/components/myday/useMyDay";
export const x = [total, useMyDay];
