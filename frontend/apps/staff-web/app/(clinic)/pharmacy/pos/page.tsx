"use client";

import { useTranslations } from "next-intl";
import AccessGate from "../../../components/ui/AccessGate";
import PosScreen from "./PosScreen";

// PHL-019: the fast counter sale. A sale creates an invoice (POST /billing/otc-checkout: billing:create) from the pharmacy's
// item index (GET /pharmacy/items/index: pharmacy:dispense), so the screen mounts, and fetches, only for a role holding both.
// PHL-020 wires its paper-prescription dialog here: pass `paperRx` to PosScreen (see its props).
export default function PharmacyPosPage() {
  const t = useTranslations("checkout.pos");
  return (
    <AccessGate permission="billing:create" surface={t("surface")}>
      <AccessGate permission="pharmacy:dispense" surface={t("surface")}>
        <PosScreen />
      </AccessGate>
    </AccessGate>
  );
}
