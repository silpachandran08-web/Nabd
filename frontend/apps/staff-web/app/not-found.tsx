import RouteNotFound from "./components/ui/RouteNotFound";

// FEF-029: last-resort 404 (outside every route group, so no shell). Unknown URLs normally reach the group not-found via each group's catch-all.
export default function NotFound() {
  return <RouteNotFound home="/" />;
}
