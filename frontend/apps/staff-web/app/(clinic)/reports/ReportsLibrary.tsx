"use client";

import { Chart, StatTile } from "@nabd/ui";
import { formatNumber, moneyFormatter } from "@nabd/i18n";
import { keepPreviousData, useInfiniteQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useMemo, useRef, useState } from "react";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import { ApiError, api } from "../../lib/api";
import { useClinicTimeZone } from "../../lib/useClinicTimeZone";
import { can } from "../../lib/permissions";
import { usePaymentMethodLabel } from "../../lib/paymentMethodText";
import { key as queryKey } from "../../lib/query";
import { getIdentity } from "../../lib/session";
import { GROUPS, decodeBase64, framePath, presetRange, rangeProblem, reportHref, visibleReports, words, type Preset } from "./libraryModel";
import s from "./library.module.css";

// ADM-047: the reports library. Library list left, report right: range filters, KPI tiles (tap opens the rows behind them), the rule,
// a chart from the shared chart kit, the drill-down table (50 rows per page) and the audited export bar. Everything shown comes from
// GET /v1/reports/{key}; exports go through POST /v1/reports/{key}/exports (absent without reports:export).
type Kpi = { key: string; label: string; value: number | string; format: "money" | "count" | "percent" | "minutes" | "number" };
type Col = { key: string; label: string; type: "text" | "money" | "number" | "date" | "datetime" };
type Frame = {
  reportId: string; group: string; title: string; from: string; to: string; rule: string; scopeNote: string | null; asOf: string | null; currency: string;
  kpis: Kpi[]; chart: { kind: "column" | "bar"; title: string; time: boolean; categories: string[]; series: { id: string; label: string; values: (number | string)[] }[] } | null;
  columns: Col[]; rows: Record<string, unknown>[]; nextCursor: string | null; masked: boolean; aggregateOnly: boolean;
};
type Exported = { contentType: string; fileName: string; rows: number; masked: boolean; contentBase64: string };
type Export = { state: "idle" } | { state: "busy" } | { state: "done"; rows: number; masked: boolean } | { state: "error"; message: string };

const PRESETS: Preset[] = ["d7", "d30", "month"];
const dayLabel = (isoDay: string, locale: string) => {
  const [y, m, d] = isoDay.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString(locale, { day: "numeric", month: "short", timeZone: "UTC" });
};

export default function ReportsLibrary() {
  const t = useTranslations("shell.reportsLibrary");
  const methodLabel = usePaymentMethodLabel();
  const locale = useLocale();
  const tz = useClinicTimeZone();
  const router = useRouter();
  const params = useSearchParams();
  const drillRef = useRef<HTMLHeadingElement>(null);
  const [exp, setExp] = useState<Export>({ state: "idle" });

  const catalog = visibleReports((p) => can(p as Parameters<typeof can>[0]));
  const def = catalog.find((r) => r.key === params.get("report")) ?? catalog[0];
  const dflt = useMemo(() => presetRange("d30", new Date()), []);
  const from = params.get("from") ?? dflt.from;
  const to = params.get("to") ?? dflt.to;
  const branch = params.get("branch");
  const problem = rangeProblem(from, to);

  const go = (next: Record<string, string>) => {
    const q = new URLSearchParams(params.toString());
    Object.entries(next).forEach(([k, v]) => q.set(k, v));
    setExp({ state: "idle" });
    router.replace(`/reports?${q}`, { scroll: false });
  };

  const q = useInfiniteQuery({
    queryKey: queryKey("reports-library", { report: def?.key ?? "", from, to, branch: branch ?? "" }),
    queryFn: ({ pageParam, signal }) => api.get<Frame>(framePath({ key: def!.key, from, to, branch, cursor: pageParam || null }), { signal }),
    initialPageParam: "",
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: !!def && !problem,
    placeholderData: keepPreviousData, // a refetch keeps the report on screen
    staleTime: 30_000,
  });

  if (!def) return <main className={s.page}><EmptyState title={t("noAccess")} /></main>;

  const first = q.data?.pages[0];
  const rows = q.data?.pages.flatMap((p) => p.rows) ?? [];
  const cur = moneyFormatter(first?.currency ?? "INR");
  const tx = (prefix: string, k: string, fallback: string) => (t.has(`${prefix}_${k}`) ? t(`${prefix}_${k}`) : fallback);
  const num = (v: unknown, max = 0) => formatNumber(Number(v), { max });
  const when = (v: unknown, opts: Intl.DateTimeFormatOptions) => new Date(String(v)).toLocaleString(locale, { ...opts, ...(tz ? { timeZone: tz } : {}) });

  const kpiText = (k: Kpi) =>
    k.format === "money" ? cur.format(Number(k.value)) : k.format === "percent" ? `${num(k.value, 1)}%` : k.format === "minutes" ? t("kpi_minutes", { n: num(k.value, 1) })
      : num(k.value, k.format === "number" ? 1 : 0);

  const cell = (c: Col, v: unknown) => {
    if (v == null || v === "") return "—";
    if (c.type === "money") return cur.format(Number(v));
    if (c.type === "number") return num(v, 1);
    if (c.type === "date") return dayLabel(String(v), locale);
    if (c.type === "datetime") return when(v, { dateStyle: "medium", timeStyle: "short" });
    return c.key === "method" ? methodLabel(String(v)) : c.key === "status" || c.key === "source" ? words(v) : String(v);
  };

  const openRows = () => {
    drillRef.current?.scrollIntoView({ block: "start" });
    drillRef.current?.focus();
  };

  async function exportAs(format: "xlsx" | "csv" | "pdf") {
    setExp({ state: "busy" });
    try {
      const r = await api.post<Exported>(`/reports/${def!.key}/exports`, { format, from, to, ...(branch ? { branch } : {}) });
      const url = URL.createObjectURL(new Blob([decodeBase64(r.contentBase64)], { type: r.contentType }));
      const a = document.createElement("a");
      a.href = url;
      a.download = r.fileName;
      a.click();
      URL.revokeObjectURL(url);
      setExp({ state: "done", rows: r.rows, masked: r.masked });
    } catch (e) {
      setExp({ state: "error", message: e instanceof ApiError && e.status === 422 ? t("exportTooLarge") : t("exportFailed") });
    }
  }

  const chart = first?.chart;
  const chartLabels = Object.fromEntries(["table", "csv", "loading", "empty", "error", "retry", "noPermission", "other", "allZero", "singlePoint", "keys", "noValue"].map((k) => [k, t(`chart_${k}`)]));
  const forbidden = q.error instanceof ApiError && q.error.status === 403;

  return (
    <main className={s.page}>
      <h1 className={s.title}>{t("title")}</h1>
      <div className={s.layout}>
        <nav className={s.list} aria-label={t("listLabel")}>
          {GROUPS.map((g) => {
            const items = catalog.filter((r) => r.group === g);
            return items.length === 0 ? null : (
              <div key={g} style={{ display: "contents" }}>
                <p className={s.group}>{t(`group${g[0].toUpperCase()}${g.slice(1)}`)}</p>
                {items.map((r) => (
                  <Link key={r.key} href={reportHref(r.key, { from, to, branch: branch ?? undefined })} scroll={false} replace
                    className={r.key === def.key ? s.itemOn : s.item} aria-current={r.key === def.key ? "page" : undefined}>
                    {t(`report_${r.key}`)}
                  </Link>
                ))}
              </div>
            );
          })}
          <Link href="/reports?view=classic" className={s.link}>{t("classicLink")}</Link>
        </nav>

        <div className={s.body}>
          <section className={`${s.card} ${q.isPlaceholderData ? s.dim : ""}`} aria-busy={q.isFetching && !q.isFetchingNextPage}>
            <div>
              <p className={s.eyebrow}>{t(`group${def.group[0].toUpperCase()}${def.group.slice(1)}`)}</p>
              <h2 className={s.h2}>{t(`report_${def.key}`)}</h2>
            </div>

            <div className={s.filters}>
              <div className={s.segmented} role="group" aria-label={t("presets")}>
                {PRESETS.map((p) => {
                  const r = presetRange(p, new Date());
                  return <button key={p} type="button" className={r.from === from && r.to === to ? s.segOn : s.seg} onClick={() => go(r)}>{t(`preset_${p}`)}</button>;
                })}
              </div>
              <label className={s.field}>{t("from")}
                <input type="date" className={s.date} value={from} max={to} onChange={(e) => e.target.value && go({ from: e.target.value })} />
              </label>
              <label className={s.field}>{t("to")}
                <input type="date" className={s.date} value={to} min={from} onChange={(e) => e.target.value && go({ to: e.target.value })} />
              </label>
              <span className={s.chip}>{t("facility")}: {getIdentity()?.tenantName ?? ""}</span>
            </div>
            {problem && <p className={s.problem} role="alert">{t(problem === "invalid" ? "rangeInvalid" : problem === "order" ? "rangeOrder" : "rangeLong")}</p>}

            {!problem && q.isPending && <Skeleton rows={6} label={t("loading")} />}
            {!problem && q.isError && !first && (forbidden
              ? <EmptyState title={t("noAccess")} />
              : <ErrorState message={t("loadFailed")} onRetry={() => void q.refetch()} retryLabel={t("retry")} />)}

            {first && !problem && (
              <>
                <div className={s.kpis}>
                  {first.kpis.map((k) => (
                    <button key={k.key} type="button" className={s.kpiBtn} onClick={openRows} aria-label={t("openRows", { label: tx("kpi", k.key, k.label) })}>
                      <StatTile label={tx("kpi", k.key, k.label)} value={kpiText(k)} />
                    </button>
                  ))}
                </div>
                <p className={s.rule}><span aria-hidden="true">ⓘ</span><span>{first.rule}</span></p>
                {first.asOf && <p className={s.note}>{t("asOf", { time: when(first.asOf, { dateStyle: "medium", timeStyle: "short" }) })}</p>}
                {first.scopeNote && <p className={s.note}>{first.scopeNote}</p>}
                {chart && (
                  <Chart
                    kind={chart.kind}
                    title={tx("chartTitle", def.key, chart.title)}
                    categories={chart.time ? chart.categories.map((c) => dayLabel(c, locale)) : chart.categories}
                    series={chart.series.map((x) => ({ id: x.id, label: tx("chartTitle", def.key, chart.title), values: x.values.map(Number) }))}
                    time={chart.time}
                    categoryLabel={t(chart.time ? "chartCategory_day" : "chartCategory_name")}
                    formatValue={def.chart === "money" ? (n) => cur.format(n) : (n) => num(n, 1)}
                    csv={false} // the screen has its own audited export below: one export path
                    labels={{ ...chartLabels, tooMany: t("chart_tooMany") }}
                  />
                )}
              </>
            )}
          </section>

          {first && !problem && (
            <section className={s.card} aria-labelledby="rl-drill">
              <h2 id="rl-drill" ref={drillRef} tabIndex={-1} className={s.h2}>{t("drillTitle")}</h2>
              {first.aggregateOnly ? <p className={s.note}>{t("aggregateOnly")}</p>
                : rows.length === 0 ? <EmptyState title={t("rowsEmpty")} />
                : (
                  <>
                    {first.masked && <p className={s.note} role="note">{t("masked")}</p>}
                    <div className={s.tableWrap} role="region" aria-label={t("drillTitle")} tabIndex={0}>
                      <table className={s.table}>
                        <caption className="nbu-vh">{t("rowsShown", { count: rows.length })}</caption>
                        <thead><tr>{first.columns.map((c) => <th key={c.key} scope="col" className={c.type === "money" || c.type === "number" ? s.num : undefined}>{tx("col", c.key, c.label)}</th>)}</tr></thead>
                        <tbody>
                          {rows.map((r, i) => (
                            <tr key={i}>{first.columns.map((c) => <td key={c.key} className={c.type === "money" || c.type === "number" ? s.num : undefined}>{cell(c, r[c.key])}</td>)}</tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    <p className={s.note} role="status">{t("rowsShown", { count: rows.length })}</p>
                    {q.hasNextPage && <div className={s.btns}><button type="button" className={s.btn} disabled={q.isFetchingNextPage} onClick={() => void q.fetchNextPage()}>{q.isFetchingNextPage ? t("loadingMore") : t("loadMore")}</button></div>}
                    {q.isFetchNextPageError && <p className={s.problem} role="alert">{t("loadFailed")}</p>}
                  </>
                )}
            </section>
          )}

          {first && !problem && can("reports:export") && (
            <section className={`${s.card} ${s.deliv}`} aria-label={t("exportTitle")}>
              <div>
                <p className={s.eyebrow}>{t("exportTitle")}</p>
                {exp.state === "busy" && <p className={s.note} role="status">{t("exporting")}</p>}
                {exp.state === "done" && <p className={s.note} role="status">{t(exp.masked ? "exportedMasked" : "exported", { count: exp.rows })}</p>}
                {exp.state === "error" && <p className={s.problem} role="alert">{exp.message}</p>}
              </div>
              <div className={s.btns}>
                {(["xlsx", "csv", "pdf"] as const).map((f) => (
                  <button key={f} type="button" className={s.btn} disabled={exp.state === "busy"} onClick={() => void exportAs(f)}>{t(f === "xlsx" ? "excel" : f)}</button>
                ))}
              </div>
            </section>
          )}
        </div>
      </div>
    </main>
  );
}
