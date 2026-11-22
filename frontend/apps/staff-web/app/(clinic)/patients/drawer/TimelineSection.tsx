"use client";

import { useInfiniteQuery } from "@tanstack/react-query";
import styles from "../patients.module.css";
import { Skeleton } from "../../../components/ui/StateViews";
import { api } from "../../../lib/api";
import { formatClinicDate } from "../../../lib/clinicTime";
import { useClinicTimeZone } from "../../../lib/useClinicTimeZone";
import { key } from "../../../lib/query";
import type { TimelinePage } from "./usePatientQueries";

// ADM-013: with include=imported the page also carries read-only past visits from the clinic's old software.
type Imported = { kind?: string | null; documentId?: string | null; doctorName?: string | null; bill?: string | null };
type Entry = TimelinePage["data"][number] & Imported;

// Encounter timeline: own paged query, own loading / error state. A failed "Load more" keeps what is shown; press it again.
export default function TimelineSection({ patientId }: { patientId: string }) {
  const tz = useClinicTimeZone(); // an imported visit is midnight on the clinic's clock: the browser's zone would show the day before
  const q = useInfiniteQuery({
    queryKey: key("patient-timeline", { id: patientId }), initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => api.get<TimelinePage>(`/clinical/patients/${patientId}/timeline?limit=10&include=imported${pageParam ? `&cursor=${encodeURIComponent(pageParam)}` : ""}`),
    getNextPageParam: (last) => last.page.nextCursor ?? undefined,
  });
  const timelineLoad = q.isPending ? "loading" : q.isError && !q.data ? "error" : "ok";
  const timeline: Entry[] = (q.data?.pages ?? []).flatMap((p) => p.data);
  const timelineCursor = q.hasNextPage;
  const timelineLoadingMore = q.isFetchingNextPage;
  return (
                      <div className={styles.section}>
                        <div className={styles.sectionLabel}>Encounter timeline</div>
                        {timelineLoad === "error" ? (
                          <div className={styles.errorInline} role="alert">Couldn&apos;t load the encounter timeline.</div>
                        ) : timelineLoad === "loading" ? (
                          <Skeleton rows={2} label="Loading timeline" />
                        ) : timeline.length === 0 ? (
                          <div className={styles.muted}>No completed visits yet</div>
                        ) : (
                          timeline.map((e) => (
                            <div key={e.queueEntryId ?? e.documentId} className={styles.timelineRow}>
                              <span className={styles.muted}>{formatClinicDate(e.occurredAt, undefined, tz)}{e.kind === "imported_history" ? ` · Imported history · ${e.doctorName ?? ""}` : ""}</span>
                              <span>{e.diagnosis || e.assessment || "—"}</span>
                              {e.kind === "imported_history" && e.diagnosis && e.assessment && <span>{e.assessment}</span>}
                              {e.medications && <span className={styles.muted}>{e.medications}</span>}
                              {e.bill && <span className={styles.muted}>Bill {e.bill}</span>}
                            </div>
                          ))
                        )}
                        {timelineCursor && (
                          <button className={styles.linkBtn} onClick={() => q.fetchNextPage()} disabled={timelineLoadingMore}>
                            {timelineLoadingMore ? "Loading…" : "Load more"}
                          </button>
                        )}
                      </div>
  );
}
