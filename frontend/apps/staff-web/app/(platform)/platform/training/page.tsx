"use client";
// PLT-054: every Nabd operator takes the data safety training here (first week after joining, then yearly). Any operator
// session; the module and its text are the same as the clinic staff's (shell/TrainingModule, help catalogue en + hi).
import styles from "../platform.module.css";
import TrainingModule from "../../../shell/TrainingModule";
import { platformApi } from "../platformApi";
import { PLATFORM_QK } from "../surfaces";

export default function PlatformTrainingPage() {
  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Data safety training</h1>
          <p className={styles.subtitle}>Required in your first week at Nabd and again every year. Your result is saved on your operator account.</p>
        </div>
      </div>
      <div className={styles.card}>
        <TrainingModule client={platformApi} path="/platform/training/completions" queryKey={[...PLATFORM_QK, "training", "me"]} />
      </div>
    </main>
  );
}
