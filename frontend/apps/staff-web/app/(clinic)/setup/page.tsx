"use client";

import { Suspense, useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import styles from "./setup.module.css";
import { SetupTabBody, useSetupTab } from "./setupTab";
import { isSetupTab, tabHref } from "./setupTabs";

type ChecklistItem = { step: string; status: "pending" | "skipped" | "done"; skippedAt: string | null; doneAt: string | null };

// FEF-032: /setup is the Checklist tab. The old /setup?tab=<key> links (sidebar, Overview, wizard, go-live Fix links)
// are answered here: Departments keeps its card on /setup, every other known tab is replaced by its own route.
export default function SetupHome() {
  return (
    <Suspense fallback={null}>
      <SetupHomeInner />
    </Suspense>
  );
}

function SetupHomeInner() {
  const router = useRouter();
  const tabParam = useSearchParams().get("tab");
  const moved = isSetupTab(tabParam) && tabParam !== "checklist" && tabParam !== "departments" ? tabHref(tabParam) : null;
  useEffect(() => {
    if (moved) router.replace(moved, { scroll: false });
  }, [moved, router]);
  if (moved) return null;
  if (tabParam === "departments") {
    return (
      <div className={styles.card}>
        <h2 className={styles.cardTitle}>Departments</h2>
        <button className={styles.btn} onClick={() => router.push("/setup/departments")}>Open Departments</button>
      </div>
    );
  }
  return <ChecklistTab />;
}

function ChecklistTab() {
  const tab = useSetupTab<{ checklist: ChecklistItem[] }>({ checklist: "/setup/checklist" }, { checklist: [] });
  const markStep = async (step: string, status: "skipped" | "done") => {
    if (await tab.post(`/setup/checklist/${step}/${status === "skipped" ? "skip" : "complete"}`, { status })) {
      tab.loadAll();
    }
  };
  return (
    <SetupTabBody tab={tab}>
      <div className={styles.card}>
        <h2 className={styles.cardTitle}>Setup checklist</h2>
        <div className={styles.checklist}>
          {tab.data.checklist.map((item) => (
            <div key={item.step} className={styles.checkItem}>
              <span className={styles.checkLabel}>{item.step.replace(/_/g, " ")}</span>
              <div className={styles.checkActions}>
                <span className={`${styles.pill} ${item.status === "done" ? styles.pillDone : item.status === "skipped" ? styles.pillSkipped : styles.pillPending}`}>{item.status}</span>
                {item.status !== "done" && <button className={styles.smallBtn} onClick={() => markStep(item.step, "done")}>Mark done</button>}
                {item.status !== "skipped" && <button className={styles.smallBtn} onClick={() => markStep(item.step, "skipped")}>Skip</button>}
              </div>
            </div>
          ))}
        </div>
      </div>
    </SetupTabBody>
  );
}
