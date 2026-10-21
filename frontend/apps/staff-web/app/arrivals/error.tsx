"use client";

import { useQueryClient } from "@tanstack/react-query";
import styles from "../components/arrivals/arrivals.module.css";
import ArrivalsErrorPanel from "../components/arrivals/ArrivalsErrorPanel";
import { prefix } from "../lib/query";

// FEF-028: anything that throws outside the dialogs (the dialogs have their own boundary) lands here.
export default function ArrivalsError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  void error; // not shown: no PHI or stack in the UI; Next already logs it
  const qc = useQueryClient();
  // The bad render may have come from cached data: drop it and read the queue again before the page remounts.
  const retry = () => { void qc.resetQueries({ queryKey: prefix("queue") }); reset(); };
  return (
    <main className={styles.page}>
      <ArrivalsErrorPanel title="Arrivals couldn't be shown." message="Nothing was lost. Try again." actionLabel="Try again" onRecover={retry} />
    </main>
  );
}
