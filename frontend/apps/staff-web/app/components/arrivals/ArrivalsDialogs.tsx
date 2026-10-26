"use client";

// The one dialog slot of the Arrivals page: `dlg` says which dialog is open (only one at a time traps focus).
import { useRouter } from "next/navigation";
import type { Dispatch, SetStateAction } from "react";
import type { RowHandlers } from "./board/RowActions";
import { CheckInDialog, CheckInResultDialog, type CheckInResult } from "./CheckInDialog";
import CheckinPicker from "./dialogs/CheckinPicker";
import CheckoutPicker from "./dialogs/CheckoutPicker";
import { DelayForm, DelayPicker } from "./dialogs/DelayDialogs";
import DetailRail from "./dialogs/DetailRail";
import MoveDialog from "./dialogs/MoveDialog";
import PriorityDialog from "./dialogs/PriorityDialog";
import VitalsDialog from "./dialogs/VitalsDialog";
import type { AppointmentOption, DoctorQueue, Grants, Row, Staff } from "./types";
import WalkInDialog from "./walk-in/WalkInDialog";

export type Dlg =
  | { k: "rail"; id: string } | { k: "walkin"; mode?: "new" } | { k: "vitals"; id: string } | { k: "priority"; id: string }
  | { k: "delay"; doctorId: string } | { k: "delayPicker" } | { k: "checkin" } | { k: "checkinTarget"; a: AppointmentOption }
  | { k: "move" } | { k: "checkoutPicker" } | { k: "result"; r: CheckInResult };

export default function ArrivalsDialogs({ dlg, setDlg, rows, staff, doctorQueues, grants, region, sendingId, h, invalidateQueue }: {
  dlg: Dlg | null; setDlg: Dispatch<SetStateAction<Dlg | null>>; rows: Row[]; staff: Staff[]; doctorQueues: DoctorQueue[]; grants: Grants;
  region?: string; sendingId?: string; h: RowHandlers; invalidateQueue: () => unknown;
}) {
  const router = useRouter();
  if (!dlg) return null;
  const close = () => setDlg(null);
  const saved = () => { setDlg(null); void invalidateQueue(); };
  switch (dlg.k) {
    case "rail": {
      const row = rows.find((r) => r.id === dlg.id);
      return row ? <DetailRail row={row} doctorName={staff.find((s) => s.id === row.doctorId)?.name ?? "—"} grants={grants} sendingId={sendingId} h={h} onClose={close} /> : null;
    }
    case "walkin":
      return <WalkInDialog staff={staff} grants={grants} region={region} mode={dlg.mode} onClose={close} onCheckedIn={(r) => { setDlg({ k: "result", r }); void invalidateQueue(); }} />;
    case "result": return <CheckInResultDialog result={dlg.r} onClose={close} />;
    case "checkinTarget":
      return (
        <CheckInDialog
          patient={{ id: dlg.a.patientId, name: dlg.a.patientName, nextAppointmentToday: true, nextAppointmentId: dlg.a.id, nextDoctorId: dlg.a.doctorId, nextDoctorName: dlg.a.doctorName, nextAppointment: dlg.a.startTime }}
          onClose={close} onCheckedIn={() => void invalidateQueue()} onDone={close} />
      );
    case "vitals": return <VitalsDialog entryId={dlg.id} onClose={close} onSaved={saved} />;
    case "priority": return <PriorityDialog entryId={dlg.id} onClose={close} onSaved={saved} />;
    case "delay": return <DelayForm doctor={staff.find((s) => s.id === dlg.doctorId)} waiting={doctorQueues.find((d) => d.id === dlg.doctorId)?.waiting ?? 0} onClose={close} />;
    case "delayPicker": return <DelayPicker staff={staff} onPick={(doctorId) => setDlg({ k: "delay", doctorId })} onClose={close} />;
    case "checkoutPicker": return <CheckoutPicker rows={rows} onPick={(id) => { setDlg(null); router.push(`/checkout/${id}`); }} onClose={close} />;
    case "checkin": return <CheckinPicker rows={rows} staff={staff} patientsView={grants.patientsView} onPick={(a) => setDlg({ k: "checkinTarget", a })} onClose={close} />;
    case "move": return <MoveDialog rows={rows} onClose={close} onMoved={saved} />;
  }
}
