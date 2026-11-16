// COM-052: recall chip rules shared by the follow-up panel and the close summary (pure, unit-tested).
import { formatClinicDate } from "../../lib/clinicTime.ts";

export type Recall = { recallId: string; dueDate: string; status: string; intervalDays: number | null; bookedAppointmentId: string | null };

/** Chip id -> days. 3 and 6 months are 90 and 180 days: the API takes days only. */
export const RECALL_CHIPS: { id: string; days: number }[] = [
  { id: "1w", days: 7 }, { id: "2w", days: 14 }, { id: "4w", days: 28 }, { id: "3m", days: 90 }, { id: "6m", days: 180 },
];
export const MAX_RECALL_DAYS = 730;

/** Same range as the server (422 INTERVAL_OUT_OF_RANGE): whole days, 1..730. */
export function validRecallDays(days: number): boolean {
  return Number.isInteger(days) && days >= 1 && days <= MAX_RECALL_DAYS;
}

/** The close-summary date for a recall: null = none set, undefined = not known (loading/failed). dueDate is a clinic calendar date. */
export function recallLabel(r: Recall | null | undefined): string | null | undefined {
  return r === undefined || r === null ? r : formatClinicDate(r.dueDate);
}
