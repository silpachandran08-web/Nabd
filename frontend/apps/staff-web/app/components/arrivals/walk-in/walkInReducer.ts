// FEF-028: the walk-in form as one reducer (was 21 useState on the page). Pure; no runtime imports.
import type { PatientResponse } from "@nabd/api-client/generated";
import type { DuplicateCandidate } from "@nabd/api-client/types";

export type WalkInState = {
  query: string; results: PatientResponse[]; selected: PatientResponse | null; newMode: boolean; candidates: DuplicateCandidate[];
  name: string; phone: string; dob: string; gender: string;
  doctorId: string; complaint: string; paymentMode: string;
  guardianQuery: string; guardian: PatientResponse | null; guardianConsent: boolean; guardianChannel: string;
  error: string | null; fieldErrs: Record<string, string>;
};

export const initialWalkIn: WalkInState = {
  query: "", results: [], selected: null, newMode: false, candidates: [],
  name: "", phone: "", dob: "", gender: "male", doctorId: "", complaint: "", paymentMode: "cash",
  guardianQuery: "", guardian: null, guardianConsent: false, guardianChannel: "", error: null, fieldErrs: {},
};

export type WalkInAction =
  | { type: "query"; value: string }
  | { type: "results"; results: PatientResponse[] }
  | { type: "select"; patient: PatientResponse }
  | { type: "useCandidate"; patient: PatientResponse }
  | { type: "clearSelected" }
  | { type: "newMode" }
  | { type: "field"; field: "name" | "phone" | "dob" | "gender"; value: string }
  | { type: "doctor"; value: string }
  | { type: "complaint"; value: string }
  | { type: "payment"; value: string }
  | { type: "guardianQuery"; value: string }
  | { type: "guardianPick"; patient: PatientResponse }
  | { type: "guardianClear" }
  | { type: "guardianConsent"; value: boolean }
  | { type: "guardianChannel"; value: string }
  | { type: "error"; message: string | null; fieldErrs?: Record<string, string> }
  | { type: "duplicates"; candidates: DuplicateCandidate[]; message: string };

export function walkInReducer(s: WalkInState, a: WalkInAction): WalkInState {
  switch (a.type) {
    case "query": return { ...s, query: a.value, selected: null, newMode: false, candidates: [], results: a.value.trim().length < 2 ? [] : s.results };
    case "results": return { ...s, results: a.results };
    case "select": return { ...s, selected: a.patient, results: [] };
    case "useCandidate": return { ...s, selected: a.patient, candidates: [], error: null };
    case "clearSelected": return { ...s, selected: null, query: "" };
    case "newMode": return { ...s, newMode: true };
    case "field": return { ...s, [a.field]: a.value, fieldErrs: {} };
    case "doctor": return { ...s, doctorId: a.value };
    case "complaint": return { ...s, complaint: a.value };
    case "payment": return { ...s, paymentMode: a.value };
    case "guardianQuery": return { ...s, guardianQuery: a.value };
    case "guardianPick": return { ...s, guardian: a.patient };
    case "guardianClear": return { ...s, guardian: null, guardianQuery: "", guardianConsent: false, guardianChannel: "" };
    case "guardianConsent": return { ...s, guardianConsent: a.value };
    case "guardianChannel": return { ...s, guardianChannel: a.value };
    case "error": return { ...s, error: a.message, fieldErrs: a.fieldErrs ?? {} };
    case "duplicates": return { ...s, candidates: [...a.candidates].sort((x, y) => y.matchScore - x.matchScore), error: a.message };
  }
}
