import { useQuery, useQueryClient } from "@tanstack/react-query";
import { STALE } from "@nabd/api-client/queries";
import type { WorklistResponse } from "@nabd/api-client/generated";
import { can } from "../../lib/permissions";
import { ApiError } from "../../lib/api";
import { usePolling } from "../../lib/usePolling";
import { adminOrdersQuery, getter, key, live, prefix, useReload } from "../../lib/query";
import { PERM, POLL_MS, waitMinutes, type ActivityEntry, type AdministrationOrder, type Entry, type ProcedureOrder, type Row, type StaffOption } from "./model";

const RECORDED_STATUSES = ["vitals_done", "in_consult", "procedures_pending", "checkout_pending", "completed"];
const list = <T,>(d: T[] | undefined): T[] => (Array.isArray(d) ? d : []);

/** FEF-031: every server read of the nursing screen (FEF-015 queries), shaped into the rows the tabs render. */
export function useNursingData(mounted: boolean) {
  const qc = useQueryClient();
  const queueView = mounted && can(PERM.queueView);
  // NUR-019: one worklist call (queue entry + patient + vitals per row) instead of a per-row fan-out, so a
  // 40-row worklist is still one request. The worklist, priority and procedures-pending lists need queue:view;
  // a nurse role without it still has the nursing:view sections (orders, procedures, activity).
  const worklistQ = useQuery({
    ...live, queryKey: key("nursing", { list: "worklist" }), queryFn: getter<WorklistResponse>("/nursing/worklist"), enabled: queueView,
  });
  const rosterQ = useQuery({ queryKey: key("roster"), queryFn: getter<StaffOption[]>("/staff/roster"), staleTime: STALE.roster, enabled: queueView });
  const adminQ = useQuery({ ...adminOrdersQuery(), enabled: mounted }); // shared with the sidebar badge
  const procQ = useQuery({
    ...live, queryKey: key("nursing", { list: "procedure-orders" }), queryFn: getter<ProcedureOrder[]>("/nursing/procedure-orders/today"), enabled: mounted,
  });
  const activityQ = useQuery({
    ...live, queryKey: key("nursing", { list: "activity" }), queryFn: getter<ActivityEntry[]>("/nursing/activity/today"), enabled: mounted,
  });
  const reload = useReload("nursing");
  // "updates live with queue state" (NB-142's acceptance bar) — polling, same as /arrivals (NB-095);
  // push-based sync is NB-096, a separate ticket.
  usePolling(reload, POLL_MS);
  // Every write here changes the worklist or the queue (and the sidebar badges reading them).
  const invalidateAll = () => Promise.all([qc.invalidateQueries({ queryKey: prefix("nursing") }), qc.invalidateQueries({ queryKey: prefix("queue") })]);

  const wErr = worklistQ.error;
  const queueForbidden = mounted && (!can(PERM.queueView) || (wErr instanceof ApiError && wErr.status === 403));
  // Keep whatever was last loaded on screen (and any open dialog mounted); a failed refresh shows an
  // inline banner with Retry instead of replacing the worklist.
  const error = !wErr || queueForbidden ? null : wErr instanceof ApiError ? "Couldn't load the nursing worklist. Try again." : "Couldn't reach the server. Check your connection and try again.";
  const loaded = mounted && (queueForbidden || worklistQ.data !== undefined) && [adminQ, procQ, activityQ].every((q) => !q.isPending);
  // A 403 on a secondary list just means that section isn't theirs; anything else is a failure
  // the nurse should hear about rather than see as a silently empty tab.
  const sectionFailed = [adminQ, procQ, activityQ, rosterQ].some((q) => q.isError && !(q.error instanceof ApiError && q.error.status === 403));
  const staff = list(rosterQ.data);
  const administrationOrders = list(adminQ.data) as AdministrationOrder[];
  const procedureOrders = list(procQ.data);
  const activity = list(activityQ.data);
  const staffMap = new Map(staff.map((s) => [s.id, s.name]));
  const all: Row[] = (queueForbidden ? [] : list(worklistQ.data?.items)).map(({ entry, patient: p, vitals: v }) => {
    const e = entry as Entry;
    return {
      ...e, patientName: p?.name ?? "Unknown patient", patientMrn: p?.mrn ?? "", doctorName: e.doctorName || (staffMap.get(e.doctorId) ?? "—"),
      allergies: p?.allergies ?? [], chronicConditions: p?.chronicConditions ?? [],
      lastVitalsAt: v?.recordedAt ?? null, vitalsOutOfRange: (v?.abnormalFlags?.length ?? 0) > 0,
      dob: p?.dob ?? null, gender: entry?.gender ?? p?.gender ?? null, hasAppointment: !!e.appointmentId, flagsUnknown: !p,
      ageYears: entry?.ageYears ?? null, wait: e.waitMinutes ?? waitMinutes(e.createdAt),
    };
  });
  // "Recorded": anyone past vitals_pending today. Rows without vitals (flow skips the step, or no clinical:view)
  // stay, shown as not recorded.
  const rows = all.filter((r) => r.status === "vitals_pending");
  const recordedRows = all.filter((r) => RECORDED_STATUSES.includes(r.status));
  const proceduresPendingRows = all.filter((r) => r.status === "procedures_pending");
  const priorityRows = all.filter((r) => r.priority);
  function retryLoad() {
    void reload();
    if (rosterQ.isError) void rosterQ.refetch();
  }
  return { rosterQ, error, loaded, queueForbidden, sectionFailed, staff, administrationOrders, procedureOrders, activity, all, rows, recordedRows, proceduresPendingRows, priorityRows, retryLoad, invalidateAll, qc };
}
