"use client";

import { can } from "../../../lib/permissions";
import { visibleWidgets } from "./registry";
import styles from "../myDay.module.css";

/** CLN-043: the profile's widget grid. Mounted as soon as the profile is known (not after the queue), so each widget's chunk and
 * read start at once and in parallel with My Day's own reads. */
export default function SpecialtyWidgets({ ids, names }: { ids: string[]; names: Record<string, string> }) {
  const shown = visibleWidgets(ids, can);
  if (shown.length === 0) return null;
  return (
    <div className={styles.widgets}>
      {shown.map(({ id, entry: { Component } }) => <Component key={id} names={names} />)}
    </div>
  );
}
