"use client";

// CLN-042: the doctor's specialty workspace, resolved from GET /v1/specialty/me (registry-driven: no per-specialty code).
import Link from "next/link";
import { useLocale, useTranslations } from "next-intl";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import { errorText } from "../../lib/api";
import { workspaceView } from "./specialtyModel";
import { useSpecialtyMe } from "./useSpecialtyMe";
import s from "./specialty.module.css";

export default function SpecialtyWorkspace() {
  const t = useTranslations("shell.specialty");
  const locale = useLocale();
  const q = useSpecialtyMe();
  if (q.isPending) return <main className={s.page}><Skeleton rows={4} label={t("loading")} /></main>;
  if (q.isError) return <main className={s.page}><ErrorState message={errorText(q.error, t("loadFailed"))} onRetry={() => q.refetch()} /></main>;
  const v = workspaceView(q.data, locale);
  if (!v) return <main className={s.page}><EmptyState icon="stethoscope" title={t("none")} hint={t("noneHint")} /></main>;
  return (
    <main className={s.page}>
      <h1 className={s.title}>{t("title", { name: v.title })}</h1>
      {v.readOnly && <p id="specialty-upgrade" className={s.upgrade} role="status">{t("expired", { name: v.title })}</p>}
      {v.actions.length > 0 && (
        <section aria-label={t("quickActions")}>
          <h2 className={s.heading}>{t("quickActions")}</h2>
          <div className={s.actions}>
            {v.actions.map((a) => a.disabled
              ? <button key={a.id} type="button" className={s.action} disabled aria-describedby="specialty-upgrade">{a.label}</button>
              : <Link key={a.id} href={a.href} className={s.action}>{a.label}</Link>)}
          </div>
        </section>
      )}
      {v.widgets.length > 0 && (
        <section aria-label={t("tools")} className={s.widgets}>
          {v.widgets.map((w) => (
            <div key={w.id} className={s.widget} data-widget={w.id}>
              <h2 className={s.heading}>{w.label}</h2>
              {w.href ? <Link href={w.href} className={s.link}>{t("openTool")}</Link> : <p className={s.muted}>{t("notBuilt")}</p>}
            </div>
          ))}
        </section>
      )}
    </main>
  );
}
