"use client";

import AccessGate from "../components/ui/AccessGate";
import MyDay from "../components/myday/MyDay";

// DOC-002: the doctor's home. Every read behind it (GET /queue, /appointments, /doctors/{id}/delay,
// /queue/wait-estimate) demands queue:view, so that is the page grant; the chronic-reviews widget
// additionally needs clinical:view and is absent without it (see MyDay).
export default function MyDayPage() {
  return (
    <AccessGate permission="queue:view" surface="My Day">
      <MyDay />
    </AccessGate>
  );
}
