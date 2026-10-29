"use client";

import Link from "next/link";
import { useTranslations } from "next-intl";
import { useQuery } from "@tanstack/react-query";
import styles from "./platform.module.css";
import Icon from "../../components/ui/Icon";
import { ErrorState, Skeleton } from "../../components/ui/StateViews";
import { platformError, platformMeQuery } from "./platformApi";
import { platformNavFor, ROLE_LABELS } from "./surfaces";

export default function PlatformHomePage() {
  const t = useTranslations("shell.platformNav");
  const me = useQuery(platformMeQuery()); // same entry the shell already loaded

  if (me.isPending) {
    return (
      <main className={styles.page}>
        <Skeleton rows={5} label="Loading Command Centre" />
      </main>
    );
  }

  if (me.isError) {
    return (
      <main className={styles.page}>
        <ErrorState message={platformError(me.error, "Couldn't load your profile. Try again.")} onRetry={() => void me.refetch()} />
      </main>
    );
  }

  const profile = me.data;
  // Command Centre is the page we're on — list only the other surfaces, in nav order.
  const items = platformNavFor(profile.permissions).filter((s) => s.href !== "/platform");

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Command Centre</h1>
          <p className={styles.subtitle}>
            Signed in as {profile.name} · {ROLE_LABELS[profile.role] ?? profile.role}
          </p>
        </div>
      </div>

      <div className={styles.card}>
        {items.length === 0 ? (
          <div className={styles.state}>Nothing else is available to your role yet.</div>
        ) : (
          <ul className={styles.navList}>
            {items.map((s) => (
              <li key={s.href}>
                <Link className={styles.navLink} href={s.href}>
                  <Icon name={s.icon} size={16} /> {t(`${s.id}.label`)}
                </Link>
              </li>
            ))}
          </ul>
        )}
      </div>
    </main>
  );
}
