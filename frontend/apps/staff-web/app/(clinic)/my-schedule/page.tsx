"use client";

import AccessGate from "../../components/ui/AccessGate";
import MySchedule from "../../components/myschedule/MySchedule";

// DOC-004: the doctor's own availability. Every read (GET /doctors/{id}/working-hours, /leave,
// /schedule/day) demands queue:view, so that is the page grant. Own working hours are always writable
// (DOC-R7); Apply leave still needs queue:edit and is absent without it.
export default function MySchedulePage() {
  return (
    <AccessGate permission="queue:view" surface="My Schedule">
      <MySchedule />
    </AccessGate>
  );
}
