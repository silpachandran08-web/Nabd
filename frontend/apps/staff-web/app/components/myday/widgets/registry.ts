// CLN-043: the my-day widget registry. A widget = a lazily loaded component (its own chunk) + the permission its data endpoint
// demands + a data hook inside the component. The profile's config.myDay.widgets (GET /v1/specialty/me) picks which show; an id
// with no entry here has no data source yet and is hidden, never stubbed (photosDue, inChairNow, labDueBack ... light up when
// their stories add an entry). GP's waiting count and registers due are My Day's own queue and chronic-review cards.
import dynamic from "next/dynamic";
import type { ComponentType } from "react";
import type { AppPermission } from "@nabd/api-client/types";

export type WidgetProps = { names: Record<string, string> };
export type WidgetEntry = { permission: AppPermission; Component: ComponentType<WidgetProps> };

export const WIDGETS: Record<string, WidgetEntry> = {
  vaccinesDueToday: { permission: "clinical:view", Component: dynamic(() => import("./VaccinesDueWidget")) },
  followUpsDue: { permission: "queue:view", Component: dynamic(() => import("./FollowUpsDueWidget")) },
};

/** The configured widgets this user gets: known ids whose permission they hold (restricted widgets are absent), config order, no repeats. */
export function visibleWidgets(ids: string[], can: (p: AppPermission) => boolean): { id: string; entry: WidgetEntry }[] {
  return [...new Set(ids)].flatMap((id) => {
    const entry = Object.hasOwn(WIDGETS, id) ? WIDGETS[id] : undefined;
    return entry && can(entry.permission) ? [{ id, entry }] : [];
  });
}
