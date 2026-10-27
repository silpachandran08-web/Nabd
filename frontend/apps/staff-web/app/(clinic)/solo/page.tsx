"use client";

import AccessGate from "../../components/ui/AccessGate";
import RequestsCard from "../../components/requests/RequestsCard";
import ExceptionsRail from "../../components/exceptions/ExceptionsRail";
import MyDay from "../../components/myday/MyDay";

// ACC-013 (solo.today): the solo doctor's home is the live queue with call-next (My Day). The booking-requests card (ACC-010) and
// the exceptions rail (ACC-032: failed self check-ins, unconfirmed bookings, hand-offs) sit above the queue.
export default function SoloTodayPage() {
  return (
    <AccessGate permission="queue:view" surface="Today">
      <MyDay requestsSlot={<><RequestsCard /><ExceptionsRail compact /></>} />
    </AccessGate>
  );
}
