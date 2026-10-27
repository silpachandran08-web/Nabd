"use client";

import { killBanner, useEntitlements } from "./lib/entitlements";

// PLT-059: staff see which feature the operator switched off for this clinic (kill switch); nothing renders otherwise.
export default function EntitlementsBanner() {
  const text = killBanner(useEntitlements());
  if (!text) return null;
  return (
    <div role="status" style={{ padding: "12px 16px", background: "var(--nb-warn-tint)", color: "var(--nb-warn-500)", borderBlockEnd: "1px solid var(--nb-border-default)" }}>
      {text}
    </div>
  );
}
