"use client";

import { Suspense } from "react";
import AccessGate from "../../components/ui/AccessGate";
import NursingWorklist from "../../components/nursing/NursingWorklist";

// E13 Nursing, Orders & Triage. FEF-031: the page only composes; the screen lives in components/nursing
// (NursingWorklist + one file per tab/dialog, server state in useNursingData).
export default function NursingPage() {
  return (
    <AccessGate permission="nursing:view" surface="Nursing">
      <Suspense fallback={null}>
        <NursingWorklist />
      </Suspense>
    </AccessGate>
  );
}
