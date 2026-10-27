"use client";

import AccessGate from "../../../components/ui/AccessGate";
import SoloConsult from "../../../components/solo/SoloConsult";

// CLN-008 solo.consult: pre-consult summary on a phone (GET /clinical/visits/{id}/summary) with a thumb-zone action bar.
export default function SoloConsultPage() {
  return (
    <AccessGate permission="clinical:view" surface="Consult summary">
      <SoloConsult />
    </AccessGate>
  );
}
