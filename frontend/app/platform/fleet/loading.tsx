import styles from "./fleet.module.css";

// Next.js shows this automatically during the route-transition gap between clicking a Command
// Centre nav link and this page's own JS chunk finishing load — without it, that gap (routinely
// 1-2s on a cold navigation) shows nothing at all, reading as a broken/unresponsive click.
export default function Loading() {
  return (
    <main className={styles.page}>
      <div className={styles.state}>Loading…</div>
    </main>
  );
}
