// FEF-028: view types shared by the Arrivals feature. Response shapes come from the generated client / domain.
import type { QueueEntry } from "@nabd/domain";
import type { StaffRosterEntry } from "@nabd/api-client/generated";

export type Entry = QueueEntry;
export type Row = Entry & { patientName: string; patientMrn: string };
export type Staff = StaffRosterEntry & { id: string; name: string };
// LIVE-04: each flag is exactly what its control's endpoint needs (see grantsNow in hooks/useArrivals).
export type Grants = { queueCreate: boolean; queueEdit: boolean; checkout: boolean; vitalsRecord: boolean; patientsView: boolean; patientsCreate: boolean };
// A today's-schedule appointment not yet checked in: the "Check in" quick action's picker (a view model, not an API type).
export type AppointmentOption = { id: string; patientId: string; patientName: string; doctorId: string; doctorName: string; startTime: string };
export const NO_GRANTS: Grants = { queueCreate: false, queueEdit: false, checkout: false, vitalsRecord: false, patientsView: false, patientsCreate: false };
export type DoctorQueue = Staff & { waiting: number; estimatedMinutes?: number; delay: { delayMinutes?: number; reason?: string } | null };
