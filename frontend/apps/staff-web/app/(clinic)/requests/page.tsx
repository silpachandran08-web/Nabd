"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import AccessGate from "../../components/ui/AccessGate";
import { Skeleton } from "../../components/ui/StateViews";
import PlannedScreen from "../../shell/PlannedScreen";
import RequestsInbox from "../../components/requests/RequestsInbox";
import { requestFilter } from "../../components/requests/requestsLogic";

// ACC-010: reception's booking-requests inbox. GET/POST /v1/booking-requests need queue:create (the booking permission).
// ADM-042: /requests?ids=a,b (the live board's "requests waiting" alert) shows only those requests.
export default function RequestsPage() {
  return (
    <PlannedScreen id="requests">
    <AccessGate permission="queue:create" surface="Booking requests">
      <Suspense fallback={<main style={{ padding: 32 }}><Skeleton rows={4} label="Loading booking requests" /></main>}><Filtered /></Suspense>
    </AccessGate>
    </PlannedScreen>
  );
}

function Filtered() {
  return <RequestsInbox only={requestFilter(useSearchParams().get("ids"))} />;
}
