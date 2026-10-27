"use client";

import { useInfiniteQuery } from "@tanstack/react-query";
import styles from "../patients.module.css";
import { Skeleton } from "../../../components/ui/StateViews";
import { api } from "../../../lib/api";
import { key } from "../../../lib/query";
import type { TimelinePage } from "./usePatientQueries";

// Encounter timeline: own paged query, own loading / error state. A failed "Load more" keeps what is shown; press it again.
export default function TimelineSection({ patientId }: { patientId: string }) {
  const q = useInfiniteQuery({
    queryKey: key("patient-timeline", { id: patientId }), initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => api.get<TimelinePage>(`/clinical/patients/${patientId}/timeline?limit=10${pageParam ? `&cursor=${encodeURIComponent(pageParam)}` : ""}`),
    getNextPageParam: (last) => last.page.nextCursor ?? undefined,
  });
  const timelineLoad = q.isPending ? "loading" : q.isError && !q.data ? "error" : "ok";
  const timeline = (q.data?.pages ?? []).flatMap((p) => p.data);
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
                            <div key={e.queueEntryId} className={styles.timelineRow}>
                              <span className={styles.muted}>{new Date(e.occurredAt).toLocaleDateString()}</span>
                              <span>{e.diagnosis || e.assessment || "—"}</span>
                              {e.medications && <span className={styles.muted}>{e.medications}</span>}
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
