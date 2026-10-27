"use client";

import AccessGate from "../../components/ui/AccessGate";
import RequestsCard from "../../components/requests/RequestsCard";
import MyDay from "../../components/myday/MyDay";
import SoloToday from "../../components/solo/SoloToday";
import { useHasMounted } from "../../lib/useHasMounted";
import { useMediaQuery } from "../../lib/useMediaQuery";

// ACC-013 (solo.today): the solo doctor's home is the live queue with call-next (My Day). CLN-008: on a phone the same
// route shows the thumb-zone list (components/solo); the booking-requests and self-check-in-exception cards wait on the
// booking routing story (ACC-009/010); nothing is faked here.
export default function SoloTodayPage() {
  const mounted = useHasMounted();
  const phone = useMediaQuery("(max-width: 640px)");
  return (
    <AccessGate permission="queue:view" surface="Today">
      {mounted && phone ? <SoloToday /> : <MyDay requestsSlot={<RequestsCard />} />}
    </AccessGate>
  );
}
