"use client";

import AccessGate from "../components/ui/AccessGate";
import RequestsCard from "../components/requests/RequestsCard";
import MyDay from "../components/myday/MyDay";

// ACC-013 (solo.today): the solo doctor's home is the live queue with call-next (My Day). The booking-requests and
// self-check-in-exception cards wait on the booking routing story (ACC-009/010); nothing is faked here.
export default function SoloTodayPage() {
  return (
    <AccessGate permission="queue:view" surface="Today">
      <MyDay requestsSlot={<RequestsCard />} />
    </AccessGate>
  );
}
