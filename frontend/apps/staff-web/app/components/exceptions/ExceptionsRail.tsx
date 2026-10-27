"use client";

import Link from "next/link";
import type { AppPermission } from "@nabd/api-client/types";
import Can from "../ui/Can";
import StatusPill from "../ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { usePermissions } from "../../lib/usePermissions";
import { ACTION_LABEL, KIND_TITLE, actionHref, actionPermission, headline, sortItems, type DeskItem } from "./exceptionsLogic";
import { useDeskExceptions } from "./useDeskExceptions";
import styles from "./exceptions.module.css";

/** The front desk's inbox of what needs a person (prototype reception.arrivals alert list; the same items on solo.today). Absent without queue:view. */
export default function ExceptionsRail({ compact = false }: { compact?: boolean }) {
  return <Can permission="queue:view"><Body compact={compact} /></Can>;
}

function Body({ compact }: { compact: boolean }) {
  const x = useDeskExceptions();
  const items = sortItems(x.items ?? []);
  return (
    <section className={styles.rail} aria-label="Needs you">
      <h2 className={styles.title}>Needs you {x.items && items.length > 0 && <span className={styles.badge}>{items.length}</span>}</h2>
      {x.notice && <div className={`${styles.notice} ${x.notice.tone === "warn" ? styles.noticeWarn : ""}`} role="status">{x.notice.text}</div>}
      {x.items === null && !x.failed && <Skeleton rows={2} label="Loading what needs you" />}
      {x.items === null && x.failed && <ErrorState message="Couldn't load this list." onRetry={() => void x.reload()} />}
      {x.items !== null && x.failed && <div className={styles.notice} role="alert">Showing the last list we loaded. <button type="button" className={styles.linkBtn} onClick={() => void x.reload()}>Retry</button></div>}
      {x.items !== null && items.length === 0 && <EmptyState icon="check" title="Nothing needs you right now." hint={compact ? undefined : "Failed self check-ins, unconfirmed bookings and patient hand-offs show up here."} />}
      <ul className={styles.list}>
        {items.map((it) => (
          <li key={it.id} className={styles.row}>
            <div className={styles.main}>
              <div className={styles.head}>
                <span className={styles.kind}>{KIND_TITLE[it.kind] ?? "Needs you"}</span>
                {it.severity === "urgent" ? <StatusPill tone="urgent" label="Urgent" /> : it.severity === "warn" ? <StatusPill tone="warn" label="Soon" /> : null}
              </div>
              <span className={styles.what}>{headline(it)}</span>
            </div>
            <div className={styles.actions}>{it.actions.map((a) => <Action key={a} item={it} action={a} x={x} />)}</div>
          </li>
        ))}
      </ul>
    </section>
  );
}

function Action({ item, action, x }: { item: DeskItem; action: string; x: ReturnType<typeof useDeskExceptions> }) {
  const { can } = usePermissions();
  const need = actionPermission(action);
  if (need && !can(need as AppPermission)) return null; // absent, never disabled
  const label = ACTION_LABEL[action] ?? action;
  const href = actionHref(item, action);
  if (href) return <Link href={href} className={styles.secondary}>{label}</Link>;
  if (action === "call" || action === "message") return <button type="button" className={styles.secondary} onClick={() => void x.openPatient(item)}>{label}</button>;
  const run = action === "confirm_arrival" ? x.confirmArrival : action === "resolve" ? x.resolve : action === "snooze" ? x.snooze : null;
  if (!run) return null;
  return <button type="button" className={action === "confirm_arrival" ? styles.primary : styles.secondary} disabled={x.busy === item.id} onClick={() => void run(item)}>{label}</button>;
}
