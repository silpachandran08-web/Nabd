import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../../../lib/api";
import { getter, key, prefix } from "../../../lib/query";
import type { Allergy, AllergyStatus, Condition, Encounter, PatientDetail, Prescription } from "../patientsModel";

// FEF-031: each drawer section reads its own endpoint through its own query, so each has its own loading / error state.
export const useDetail = (id: string) => useQuery({ queryKey: key("patient-detail", { id }), queryFn: getter<PatientDetail>(`/patients/${id}`) });

/** Register and status come together: a stale "none documented" must never sit next to a changed register. */
export const useAllergies = (id: string, enabled: boolean) => useQuery({
  queryKey: key("patient-allergies", { id }), enabled,
  queryFn: async () => {
    const [list, status] = await Promise.all([
      api.get<Allergy[]>(`/clinical/patients/${id}/allergies`),
      api.get<AllergyStatus>(`/clinical/patients/${id}/allergies/status`),
    ]);
    return { list, status };
  },
});
export const useConditions = (id: string, enabled: boolean) => useQuery({ queryKey: key("patient-conditions", { id }), enabled, queryFn: getter<Condition[]>(`/clinical/patients/${id}/conditions`) });
export const usePrescriptions = (id: string, enabled: boolean) => useQuery({ queryKey: key("patient-rx", { id }), enabled, queryFn: getter<Prescription[]>(`/clinical/patients/${id}/prescriptions`) });
export type TimelinePage = { data: Encounter[]; page: { nextCursor: string | null } };

/** The write landed; re-read the header. A failed re-read keeps the last values (never reports the write as failed). */
export function useRefreshPatient(id: string) {
  const qc = useQueryClient();
  return async () => {
    await Promise.all([
      qc.invalidateQueries({ queryKey: key("patient-detail", { id }) }),
      qc.invalidateQueries({ queryKey: prefix("patient") }), // names on Arrivals follow a correction
    ]);
  };
}
