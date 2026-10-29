"use client";

import AccessGate from "../../components/ui/AccessGate";
import FollowUps from "../../components/doctorlists/FollowUps";

// DOC-006: follow-up appointments (GET /appointments/follow-ups) demand queue:view; the chronic
// reviews section additionally needs clinical:view and is absent without it (see FollowUps).
export default function FollowUpsPage() {
  return (
    <AccessGate permission="queue:view" surface="Follow-ups">
      <FollowUps />
    </AccessGate>
  );
}
