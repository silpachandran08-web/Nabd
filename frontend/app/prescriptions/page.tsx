"use client";

import AccessGate from "../components/ui/AccessGate";
import TemplatesScreen from "../components/templates/TemplatesScreen";

// DOC-003. Reading saved sets (GET /clinical/rx-sets) needs clinical:view; editing and deleting are
// additionally gated per set by the server (owner or clinical:approve) and by clinical:edit in the UI.
export default function PrescriptionsPage() {
  return (
    <AccessGate permission="clinical:view" surface="Prescriptions & Templates">
      <TemplatesScreen />
    </AccessGate>
  );
}
