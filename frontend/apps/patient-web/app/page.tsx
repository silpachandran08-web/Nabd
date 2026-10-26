import { connection } from "next/server";
import { currentLocale, messagesFor } from "./lib/messages";
import styles from "./components/linkflow.module.css";

// No link, nothing to show: the shell never lists documents or accounts.
export default async function Home() {
  await connection();
  const m = messagesFor(await currentLocale());
  return (
    <main className={styles.page}>
      <section className={styles.card}>
        <h1 className={styles.h1}>{m.landingTitle}</h1>
        <p className={styles.muted}>{m.landingHint}</p>
      </section>
    </main>
  );
}
