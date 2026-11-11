import { useTranslations } from "next-intl";
import StatusPill from "./StatusPill";

export const LOW_STOCK = 8; // same threshold the pharmacy item master uses

// PHA-011/PHA-003: qty null/undefined = not stock-tracked (or API field absent) -> no pill.
export default function StockPill({ qty }: { qty?: number | null }) {
  const t = useTranslations("common.stock");
  if (qty == null) return null;
  if (qty <= 0) return <StatusPill tone="danger" label={t("out")} />;
  if (qty <= LOW_STOCK) return <StatusPill tone="warn" label={t("low", { qty })} />;
  return <StatusPill tone="ok" label={t("in", { qty })} />;
}

// insufficient-stock arrives as problem+json whose type ends in the slug (no separate code field).
export function stockErrorMessage(p: { type?: string; detail?: string }, fallback: string): string {
  const msg = p.detail || fallback;
  return p.type?.endsWith("/insufficient-stock") ? `${msg} Reduce the quantity or remove the item, then try again.` : msg;
}
