"use client";

import type { ReactNode } from "react";
import { useTranslations } from "next-intl";
import { ErrorState, Skeleton } from "../../ui/StateViews";
import type { WidgetData } from "./useWidgetData";
import styles from "../myDay.module.css";

/** The frame every my-day widget shares: hidden (403/404) renders nothing, then loading, error, or the widget's body. */
export default function WidgetCard<T>({ id, title, hint, count, data, children }: {
  id: string; title: string; hint?: string; count?: (d: T) => number; data: WidgetData<T>; children: (d: T) => ReactNode;
}) {
  const t = useTranslations("shell.specialty.myDay");
  if (data.state === "hidden") return null;
  return (
    <section className={styles.card} aria-label={title} data-widget={id}>
      <h2 className={styles.cardTitle}>{title} {data.state === "ok" && count && <span className={styles.count}>{count(data.data)}</span>}</h2>
      {hint && <p className={styles.hint}>{hint}</p>}
      {data.state === "loading" ? <Skeleton rows={2} label={t("loading", { name: title })} />
        : data.state === "error" ? <ErrorState message={t("failed")} onRetry={data.retry} />
        : children(data.data)}
    </section>
  );
}
