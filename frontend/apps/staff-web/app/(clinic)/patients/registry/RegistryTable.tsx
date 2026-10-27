"use client";

import { initials } from "@nabd/domain";
import styles from "../patients.module.css";
import StatusPill from "../../../components/ui/StatusPill";
import BillingStatePill from "../../../components/ui/BillingStatePill";
import { formatLastVisit } from "../../../lib/lastVisit";
import { maskPhone, nextLabel, type Perms, type Registry, type RegistryRow } from "../patientsModel";

// FEF-031: the registry table and its "Load more" row, moved unchanged from the old page.
export default function RegistryTable({ registry, perms, filter, nextCursor, moreRef, moreState, loadMore, openDrawer, onCheckIn }: {
  registry: Registry; perms: Perms; filter: string; nextCursor: string | null; moreRef: React.RefObject<HTMLDivElement | null>;
  moreState: "idle" | "loading" | "error"; loadMore: () => void; openDrawer: (id: string) => void; onCheckIn: (p: RegistryRow) => void;
}) {
  const { checkIn: canCheckIn, register: canRegister, clinicalView, billingView } = perms;
  return (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr><th scope="col">Patient</th><th scope="col">Mobile</th><th scope="col">Age</th><th scope="col">Last visit</th><th scope="col">Next appointment</th><th scope="col">Flags</th><th scope="col"><span className="srOnly">Actions</span></th></tr>
              </thead>
              <tbody>
                {registry.rows.map((p) => (
                  <tr key={p.id} className={styles.row}>
                    <td>
                      <span className={styles.patientCell}>
                        <span className={p.archived ? styles.avatarMuted : styles.avatar} aria-hidden="true">{initials(p.name)}</span>
                        <span><a className={`${styles.patientName} ${styles.rowLink}`} href={`/patients?open=${encodeURIComponent(p.id)}`}
                          onClick={(e) => { if (e.button === 0 && !e.metaKey && !e.ctrlKey && !e.shiftKey) { e.preventDefault(); openDrawer(p.id); } }}>{p.name}</a><span className={styles.mrn}>{p.mrn}</span></span>
                      </span>
                    </td>
                    <td className={styles.nowrap}>{maskPhone(p.phone)}</td>
                    <td className={styles.nowrap}>{p.age} {p.gender === "female" ? "F" : p.gender === "male" ? "M" : "O"}</td>
                    <td className={styles.nowrap}>{formatLastVisit(p.lastVisit)}</td>
                    <td>{nextLabel(p)}</td>
                    <td>
                      <span className={styles.flags}>
                        {p.archived && <StatusPill tone="neutral" label="Archived" icon="inbox" />}
                        {p.packageSessionsTotal != null && <StatusPill tone="info" icon="package" label={`Package ${p.packageSessionsUsed}/${p.packageSessionsTotal}`} />}
                        {clinicalView && p.condition && <StatusPill tone="warn" icon="pulse" label={p.condition} />}
                        {p.followUpDue && <StatusPill tone="warn" label="Follow-up due" />}
                        {/* The registry row carries no consent state, so it can't say consent exists. */}
                        {p.minor && <StatusPill tone="info" icon="user" label="Minor" />}
                        {billingView && p.balanceDue && <BillingStatePill state="outstanding" />}
                        {p.allergy && <StatusPill tone="danger" label={`Allergy: ${p.allergy}`} />}
                        {canRegister && p.duplicate && <StatusPill tone="sync" icon="users" label="Duplicate review" />}
                      </span>
                    </td>
                    <td className={styles.actionCell}>
                      {p.queueToken != null ? (
                        <span className={styles.inQueue}>In queue · T-{p.queueToken}</span>
                      ) : canCheckIn && !p.archived && (
                        <button type="button" className={styles.checkInBtn}
                          onClick={(e) => { e.stopPropagation(); onCheckIn(p); }}>Check in</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {nextCursor && (
              <div ref={moreRef} className={styles.moreRow}>
                <p className={styles.muted} role="status">
                  {moreState === "error" ? "Couldn't load more patients." : `Showing ${registry.rows.length} of ${registry.matchedTotal ?? registry.counts[filter] ?? 0}.`}
                </p>
                <button type="button" className={styles.secondaryBtn} onClick={() => loadMore()} disabled={moreState === "loading"}>
                  {moreState === "loading" ? "Loading…" : moreState === "error" ? "Try again" : "Load more"}
                </button>
              </div>
            )}
          </div>
  );
}
