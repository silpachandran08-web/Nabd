"use client";

import RouteError from "../components/ui/RouteError";

// FEF-029: a render error in any owner page lands here; the group layout (shell) stays on screen.
export default function OwnerError(props: { error: Error & { digest?: string }; reset: () => void }) {
  return <RouteError {...props} group="owner" />;
}
