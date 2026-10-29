"use client";

import RouteError from "../../components/ui/RouteError";

// FEF-031: a render error in the nursing screen lands here; the shell stays on screen.
export default function NursingError(props: { error: Error & { digest?: string }; reset: () => void }) {
  return <RouteError {...props} group="clinic" />;
}
