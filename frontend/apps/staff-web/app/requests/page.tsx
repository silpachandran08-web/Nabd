"use client";

import AccessGate from "../components/ui/AccessGate";
import RequestsInbox from "../components/requests/RequestsInbox";

// ACC-010: reception's booking-requests inbox. GET/POST /v1/booking-requests need queue:create (the booking permission).
export default function RequestsPage() {
  return (
    <AccessGate permission="queue:create" surface="Booking requests">
      <RequestsInbox />
    </AccessGate>
  );
}
