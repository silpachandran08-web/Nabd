"use client";

import AccessGate from "../components/ui/AccessGate";
import Encounters from "../components/doctorlists/Encounters";

// DOC-005: GET /clinical/encounters demands clinical:view and returns only the caller's own visits.
export default function EncountersPage() {
  return (
    <AccessGate permission="clinical:view" surface="Encounters">
      <Encounters />
    </AccessGate>
  );
}
