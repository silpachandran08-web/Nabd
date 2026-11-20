"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { formatMoney } from "@nabd/i18n/format";
import { api } from "./lib/api";
import { useEntitlements } from "./lib/entitlements";
import { can } from "./lib/permissions";
import { isOwner } from "./lib/session";
import { useHasMounted } from "./lib/useHasMounted";
import { PAY_HREF, amountDue, statusBanner, type Money, type NabdInvoice } from "./lib/tenantStatus";

// ADM-020: a suspended clinic is read-only (everyone is told why saving fails); an overdue one shows its owner the amount due
// and a pay link, and blocks nothing. Lives in the shell, above the route, so it survives navigation. Nothing renders otherwise.
export default function TenantStatusBanner() {
  const t = useTranslations("shell.tenantStatus");
  const mounted = useHasMounted();
  const ent = useEntitlements();
  const kind = statusBanner(ent, mounted && isOwner());
  const canSeePlan = mounted && can("setup:view");
  const [due, setDue] = useState<Money | null>(null);

  useEffect(() => {
    if (kind !== "overdue" || !canSeePlan) return;
    let live = true;
    void Promise.all([
      api.get<{ nextInvoice: Money | null }>("/setup/subscription").catch(() => null),
      api.get<{ items: NabdInvoice[] }>("/setup/subscription/invoices").catch(() => null),
    ]).then(([plan, invoices]) => { if (live) setDue(amountDue(invoices?.items, plan?.nextInvoice)); }); // a failed call leaves the banner without a figure
    return () => { live = false; };
  }, [kind, canSeePlan]);

  if (!kind) return null;
  const tone = kind === "suspended" ? "danger" : "warn";
  return (
    <div role={kind === "suspended" ? "alert" : "status"} data-testid={`tenant-${kind}-banner`}
      style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "var(--nb-space-12)", padding: "12px 16px",
        background: `var(--nb-${tone}-tint)`, color: `var(--nb-${tone}-500)`, borderBlockEnd: "1px solid var(--nb-border-default)" }}>
      <span>{kind === "suspended" ? t("suspended") : due ? t("overdue", { amount: formatMoney(due.amountMinor, due.currency, { minor: true }) }) : t("overdueNoAmount")}</span>
      {kind === "overdue" && canSeePlan && (
        <Link href={PAY_HREF} style={{ display: "inline-flex", alignItems: "center", minBlockSize: 44, fontWeight: 600, color: "inherit", textDecoration: "underline" }}>
          {t("pay")}
        </Link>
      )}
    </div>
  );
}
