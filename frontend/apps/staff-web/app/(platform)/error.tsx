"use client";

import RouteError from "../components/ui/RouteError";

// FEF-029: a render error in any platform page lands here; the group layout (shell) stays on screen.
export default function PlatformError(props: { error: Error & { digest?: string }; reset: () => void }) {
  return <RouteError {...props} group="platform" />;
}
