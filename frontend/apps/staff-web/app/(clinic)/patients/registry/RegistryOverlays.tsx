"use client";

import { RegisterDialog } from "../RegistryDialogs";
import { CheckInDialog } from "../../../components/arrivals/CheckInDialog";
import PhoneDataQuality from "../PhoneDataQuality";
import DuplicateSuggestions from "../DuplicateSuggestions";
import type { RegistryRow } from "../patientsModel";
import { useTranslations } from "next-intl";
import Dialog from "../../../components/ui/Dialog";
import PaperQuickEntry from "../../../components/setup/PaperQuickEntry";

// FEF-031: the registry's dialogs (check-in, number fixes, duplicate review, register, ADM-014 paper cards); the page owns which is open.
export default function RegistryOverlays({ checkInFor, fixingPhones, reviewingDuplicates, registering, typingPaper, close, setFlash, reload, openDrawer }: {
  checkInFor: RegistryRow | null; fixingPhones: boolean; reviewingDuplicates: boolean; registering: boolean; typingPaper: boolean;
  close: (which: "checkIn" | "phones" | "duplicates" | "register" | "paper") => void; setFlash: (m: string) => void; reload: () => void; openDrawer: (id: string) => void;
}) {
  const tPaper = useTranslations("shell.paperEntry");
  return (
    <>
      {typingPaper && <Dialog title={tPaper("title")} size="lg" onClose={() => { close("paper"); reload(); }}><PaperQuickEntry /></Dialog>}
      {checkInFor && (
        <CheckInDialog patient={checkInFor} onClose={() => close("checkIn")}
          onDone={(token) => { setFlash(`${checkInFor.name} checked in · T-${token}`); close("checkIn"); reload(); }} />
      )}
      {fixingPhones && <PhoneDataQuality onClose={() => close("phones")} onFixed={() => { setFlash("Mobile number corrected."); reload(); }} />}
      {reviewingDuplicates && <DuplicateSuggestions onClose={() => close("duplicates")} onChanged={reload} />}
      {registering && (
        <RegisterDialog onClose={() => close("register")}
          onRegistered={(id) => { close("register"); setFlash("Patient registered."); reload(); openDrawer(id); }}
          onOpenExisting={(id) => { close("register"); openDrawer(id); }} />
      )}
    </>
  );
}
