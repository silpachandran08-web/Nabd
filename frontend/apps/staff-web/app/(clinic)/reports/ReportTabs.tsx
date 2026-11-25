"use client";

import { useTranslations } from "next-intl";
import Link from "next/link";
import { reportHref, type ReportDef } from "./libraryModel";
import s from "./library.module.css";

// ADM-049: reports that open as tabs of one library entry (package liability: Sales, Redemption, Expiry loss). Each tab is a link,
// so it has its own URL and a tab the caller may not open is simply not in `tabs` (the catalogue is already filtered by grant).
export default function ReportTabs({ tabs, current, filters }: { tabs: ReportDef[]; current: string; filters: { from?: string; to?: string; branch?: string } }) {
  const t = useTranslations("shell.reportsLibrary");
  if (tabs.length === 0) return null;
  return (
    <nav className={s.segmented} style={{ flexWrap: "wrap" }} aria-label={t("tabsLabel")}>
      {tabs.map((r) => (
        <Link key={r.key} href={reportHref(r.key, filters)} scroll={false} replace className={r.key === current ? s.itemOn : s.item} aria-current={r.key === current ? "page" : undefined}>
          {t(`tab_${r.key}`)}
        </Link>
      ))}
    </nav>
  );
}
