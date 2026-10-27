"use client";

import AccessGate from "../../components/ui/AccessGate";
import RequestsCard from "../../components/requests/RequestsCard";
import ExceptionsRail from "../../components/exceptions/ExceptionsRail";
import MyDay from "../../components/myday/MyDay";
import SoloToday from "../../components/solo/SoloToday";
import { useHasMounted } from "../../lib/useHasMounted";
import { useMediaQuery } from "../../lib/useMediaQuery";

// ACC-013 (solo.today): the solo doctor's home is the live queue with call-next (My Day). The booking-requests card (ACC-010) and
// the exceptions rail (ACC-032: failed self check-ins, unconfirmed bookings, hand-offs) sit above the queue. CLN-008: on a phone the
// same route shows the thumb-zone list (components/solo).
export default function SoloTodayPage() {
  const mounted = useHasMounted();
  const phone = useMediaQuery("(max-width: 640px)");
  return (
    <AccessGate permission="queue:view" surface="Today">
      {mounted && phone ? <SoloToday /> : <MyDay requestsSlot={<><RequestsCard /><ExceptionsRail compact /></>} />}
    </AccessGate>
  );
}
