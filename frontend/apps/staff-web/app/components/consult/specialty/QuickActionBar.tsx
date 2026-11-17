"use client";

// CLN-043: the consult's quick-action bar, above the note: exactly the actions of the doctor's specialty profile
// (GET /v1/specialty/me, at most 6, validated in specialtyModel). No profile, a failed read or an invalid config: no bar.
import Link from "next/link";
import { useLocale, useTranslations } from "next-intl";
import { quickBar } from "../../../(clinic)/specialty/specialtyModel";
import { useSpecialtyMe } from "../../../(clinic)/specialty/useSpecialtyMe";
import p from "../consultParts.module.css";
import s from "../../../(clinic)/specialty/specialty.module.css";

export default function QuickActionBar() {
  const t = useTranslations("consult.quickActionBar");
  const locale = useLocale();
  const actions = quickBar(useSpecialtyMe().data, locale);
  if (actions.length === 0) return null;
  return (
    <nav className={p.card} aria-label={t("title")} data-quick-actions={actions.length}>
      <h3 className={p.cardTitle}>{t("title")}</h3>
      <div className={s.actions}>
        {actions.map((a) => <Link key={a.id} href={a.href} className={s.action}>{a.label}</Link>)}
      </div>
    </nav>
  );
}
