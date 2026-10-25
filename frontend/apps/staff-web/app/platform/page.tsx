"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import styles from "./platform.module.css";
import Icon from "../components/ui/Icon";
import { ErrorState, Skeleton } from "../components/ui/StateViews";
import { platformApi, platformError } from "./platformApi";
import { groupBySection, OperatorProfile, ROLE_LABELS, visibleSurfaces } from "./surfaces";

export default function PlatformHomePage() {
  const [profile, setProfile] = useState<OperatorProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);


  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    (async () => {
      try {
        setProfile(await platformApi.get<OperatorProfile>("/platform/auth/me"));
      } catch (e) {
        setError(platformError(e, "Couldn't load your profile. Try again."));
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  if (loading) {
    return (
      <main className={styles.page}>
        <Skeleton rows={5} label="Loading Command Centre" />
      </main>
    );
  }

  if (error || !profile) {
    return (
      <main className={styles.page}>
        <ErrorState message={error ?? "Something went wrong."} onRetry={load} />
      </main>
    );
  }

  // Command Centre is the page we're on — list only the other surfaces, grouped as in the nav.
  const groups = groupBySection(visibleSurfaces(profile.permissions).filter((s) => s.href !== "/platform"));

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
        {groups.length === 0 ? (
          <div className={styles.state}>Nothing else is available to your role yet.</div>
        ) : (
          groups.map((g) => (
            <section key={g.section}>
              <div className={styles.sectionLabel}>{g.section}</div>
              <ul className={styles.navList}>
                {g.items.map((s) => (
                  <li key={s.href}>
                    <Link className={styles.navLink} href={s.href}>
                      <Icon name={s.icon} size={16} /> {s.label}
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          ))
        )}
      </div>
    </main>
  );
}
