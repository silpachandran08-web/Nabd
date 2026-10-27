"use client";

// FEF-028: the Arrivals page only composes. Server state: components/arrivals/hooks/useArrivals; board, walk-in
// registration (reducer + schema), duplicate review and the dialogs live in components/arrivals.
import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { useLocale, useTranslations } from "next-intl";
import styles from "../../components/arrivals/arrivals.module.css";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import { can } from "../../lib/permissions";
import { dateLocale } from "../../components/arrivals/dateLocale";
import DeskShortcuts from "../../components/arrivals/shortcuts/DeskShortcuts";
import ArrivalsDialogs, { type Dlg } from "../../components/arrivals/ArrivalsDialogs";
import DialogBoundary from "../../components/arrivals/DialogBoundary";
import BoardTable from "../../components/arrivals/board/BoardTable";
import SideColumn from "../../components/arrivals/board/SideColumn";
import TabBar from "../../components/arrivals/board/TabBar";
import { countsOf, filterByTab, isArrivalsTab, type ArrivalsTab } from "../../components/arrivals/board/boardLogic";
import { useArrivals } from "../../components/arrivals/hooks/useArrivals";
import type { RowHandlers } from "../../components/arrivals/board/RowActions";
import DoctorBoard from "../../components/arrivals/doctors/DoctorBoard";

const tip = (label: string, key: string) => `${label} (${key})`; // tooltip = label + its key (FEF-034)

// ?tab= deep links (e.g. /arrivals?tab=checkout_pending; the sidebar's Billing & Day Close opens /billing) read
// useSearchParams, which needs a Suspense boundary or the page's static prerender breaks.
export default function ArrivalsPage() {
  return (
    <Suspense fallback={null}>
      <ArrivalsPageInner />
    </Suspense>
  );
}

function ArrivalsPageInner() {
  const router = useRouter();
  const t = useTranslations("arrivals.page");
  const locale = useLocale();
  const searchParams = useSearchParams();
  const tabParam = searchParams.get("tab");
  const byDoctor = searchParams.get("view") === "doctors"; // ACC-022: one column per doctor
  const a = useArrivals();
  const { mounted, grants, rows, staff, forbidden, error, sendRow, clearDelay } = a;
  const [dlg, setDlg] = useState<Dlg | null>(null);
  const dateLabel = mounted ? new Date().toLocaleDateString(dateLocale(locale), { weekday: "short", day: "numeric", month: "short" }) : "";

  // TopBar's "Register walk-in" (on the schedule board) lands here with ?action=walkin; open once and strip it
  // so back/refresh doesn't reopen the dialog.
  useEffect(() => {
    const action = searchParams.get("action");
    if (!mounted || (action !== "walkin" && action !== "new" && action !== "checkin")) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- a one-shot URL signal, consumed and stripped
    if (action === "checkin") { if (can("queue:create")) setDlg({ k: "checkin" }); }
    else if (can("queue:create") && can("patients:view")) setDlg(action === "new" ? { k: "walkin", mode: "new" } : { k: "walkin", q: searchParams.get("q") ?? undefined }); // q: a patient picked in Ctrl+K (FEF-034)
    router.replace("/arrivals");
  }, [mounted, searchParams, router]);

  // The URL is the tab's only state: sidebar links and tab clicks both just change ?tab=.
  const tab: ArrivalsTab = isArrivalsTab(tabParam) ? tabParam : "all";
  const selectTab = (t: ArrivalsTab) => router.replace(t === "all" ? "/arrivals" : `/arrivals?tab=${t}`, { scroll: false });
  const filtered = filterByTab(rows, tab);
  const counts = countsOf(rows);

  // Stable handlers: memoised rows skip re-rendering while the data they show is unchanged.
  const h: RowHandlers = useMemo(() => ({
    onPriority: (id) => setDlg({ k: "priority", id }),
    onSendForVitals: (row) => { setDlg(null); sendRow(row); },
    onVitals: (id) => setDlg({ k: "vitals", id }),
    onCheckout: (id) => { setDlg(null); router.push(`/checkout/${id}`); },
  }), [sendRow, router, setDlg]);
  const onOpen = useCallback((id: string) => setDlg({ k: "rail", id }), [setDlg]);
  // ACC-031: F2 / F3 / F4 open the three desk dialogs (DeskShortcuts checks the grants; the dialogs are absent, not disabled, without them).
  const openers = useMemo(() => ({ newWalkIn: () => setDlg({ k: "walkin", mode: "new" }), search: () => setDlg({ k: "walkin" }), checkIn: () => setDlg({ k: "checkin" }) }), [setDlg]);
  const hasQuickActions = grants.queueCreate || grants.queueEdit || grants.checkout;

  return (
    <main className={styles.page}>
      <DeskShortcuts openers={openers} />
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>{t("title")}</h1>
          <p className={styles.subtitle}>{t("subtitle", { date: dateLabel, count: rows.length })}</p>
        </div>
        {!forbidden && (
          <div className={styles.headerActions}>
            {/* Clinic Operations' two views (DESIGN.md): this page is the Live Queue half. */}
            <nav className={styles.segmented} aria-label={t("viewLabel")}>
              <Link className={styles.segment} href="/schedule">{t("schedule")}</Link>
              {byDoctor
                ? <><Link className={styles.segment} href="/arrivals">{t("liveQueue")}</Link><span className={styles.segmentActive} aria-current="page">{t("byDoctor")}</span></>
                : <><span className={styles.segmentActive} aria-current="page">{t("liveQueue")}</span><Link className={styles.segment} href="/arrivals?view=doctors">{t("byDoctor")}</Link></>}
            </nav>
          </div>
        )}
      </div>

      {!forbidden && !byDoctor && <TabBar tab={tab} counts={counts} onSelect={selectTab} />}

      {!forbidden && !byDoctor && hasQuickActions && (
        <div className={styles.quickActions}>
          {/* A token is only ever created as part of check-in, so walk-in and "issue token" are one action. */}
          {grants.queueCreate && grants.patientsView && <button type="button" className={styles.primaryAction} onClick={() => setDlg({ k: "walkin" })} aria-keyshortcuts="F3" title={tip(t("walkInToken"), "F3")}>{t("walkInToken")}</button>}
          {grants.queueCreate && grants.patientsView && grants.patientsCreate && <button type="button" className={styles.actionBtn} onClick={() => setDlg({ k: "walkin", mode: "new" })} aria-keyshortcuts="F2" title={tip(t("newPatient"), "F2")}>{t("newPatient")}</button>}
          {grants.queueCreate && <button type="button" className={styles.actionBtn} onClick={() => setDlg({ k: "checkin" })} aria-keyshortcuts="F4" title={tip(t("checkIn"), "F4")}>{t("checkIn")}</button>}
          {grants.queueEdit && <button type="button" className={styles.actionBtn} onClick={() => setDlg({ k: "move" })}>{t("moveQueue")}</button>}
          {grants.queueEdit && <button type="button" className={styles.actionBtn} onClick={() => setDlg({ k: "delayPicker" })}>{t("notifyDelay")}</button>}
          {grants.checkout && <button type="button" className={styles.actionBtn} onClick={() => setDlg({ k: "checkoutPicker" })}>{t("checkout")}</button>}
        </div>
      )}

      {a.actionError && (
        <div className={styles.formError} role="alert">
          {a.actionError}{" "}
          <button type="button" className={styles.linkBtn} onClick={() => a.setActionError(null)}>{t("dismiss")}</button>
        </div>
      )}

      {byDoctor && !forbidden ? <DoctorBoard /> : a.loading ? (
        <div className={styles.card}><Skeleton rows={6} label={t("loading")} /></div>
      ) : forbidden ? (
        <div className={styles.card}><EmptyState icon="shield" title={t("noAccessTitle")} hint={t("noAccessHint")} /></div>
      ) : error && rows.length === 0 ? (
        <div className={styles.card}><ErrorState message={error} onRetry={a.retryAll} retryLabel={t("retry")} /></div>
      ) : (
        <>
          {error && (
            <div className={styles.formError} role="alert">
              {error} {t("staleNote")}{" "}
              <button type="button" className={styles.linkBtn} onClick={a.retryAll}>{t("retry")}</button>
            </div>
          )}
          <div className={styles.layout}>
            <div className={styles.card}>
              {filtered.length === 0 ? (
                <EmptyState icon="users" title={t("emptyTitle")}
                  hint={tab === "all" ? t("emptyHintAll") : t("emptyHintTab")}
                  action={tab !== "all" ? <button type="button" className={styles.linkBtn} onClick={() => selectTab("all")}>{t("showAll")}</button> : undefined} />
              ) : (
                <BoardTable rows={filtered} staff={staff} grants={grants} sendingId={a.sendingId} h={h} onOpen={onOpen} />
              )}
            </div>
            <SideColumn doctors={a.doctorQueues} rosterFailed={a.rosterFailed} onRetryRoster={() => void a.refetchRoster()} callbacks={a.callbackList} grants={grants}
              onAnnounce={(doctorId) => setDlg({ k: "delay", doctorId })} onClear={(id) => void clearDelay(id)} />
          </div>
        </>
      )}

      <DialogBoundary resetKey={dlg} onRecover={() => setDlg(null)}>
        <ArrivalsDialogs dlg={dlg} setDlg={setDlg} rows={rows} staff={staff} doctorQueues={a.doctorQueues} grants={grants} region={a.region}
          sendingId={a.sendingId} h={h} invalidateQueue={a.invalidateQueue} />
      </DialogBoundary>
    </main>
  );
}
