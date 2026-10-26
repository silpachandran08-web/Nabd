"use client";

import RouteError from "../components/ui/RouteError";

// FEF-029: a render error in any clinic page lands here; the group layout (shell) stays on screen.
export default function ClinicError(props: { error: Error & { digest?: string }; reset: () => void }) {
  return <RouteError {...props} group="clinic" />;
}
