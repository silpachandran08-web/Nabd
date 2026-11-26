"use client";

// PHL-043: the 'Tests' card on the consult screen. The clinic's lab module decides what it is: the full order form (LabOrderPanel), or only the
// order sheet for an outside lab (LabExternalSheet). Nothing is mounted or requested before the first entitlements answer; a failed answer
// shows a manual retry, never an automatic one (S17 rule).
import { useTranslations } from "next-intl";
import { useEntitlementsStatus } from "../../lib/entitlements";
import { getPermissions } from "../../lib/session";
import { testsMode } from "../../lib/labOrders";
import { ErrorState, Skeleton } from "../ui/StateViews";
import ExternalSheet from "./LabExternalSheet";
import OrderPanel from "./LabOrderPanel";
import type { Row } from "./shared";
import s from "./consultParts.module.css";

export default function LabTestsCard({ entry }: { entry: Row }) {
  const t = useTranslations("consult.labTests");
  const { ent, settled, retry } = useEntitlementsStatus();
  const mode = testsMode(ent, settled, getPermissions());
  if (mode === "none") return null;
  return (
    <section className={s.card} aria-label={t("title")}>
      <h3 className={s.cardTitle}>{t("title")}</h3>
      {mode === "wait" && <Skeleton rows={2} label={t("loading")} />}
      {mode === "unknown" && <ErrorState message={t("entitlementsFailed")} onRetry={retry} />}
      {mode === "orders" && <OrderPanel entry={entry} />}
      {mode === "external" && <ExternalSheet entry={entry} />}
    </section>
  );
}
