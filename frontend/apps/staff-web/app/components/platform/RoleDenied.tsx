import { EmptyState } from "../ui/StateViews";

// Wireframe pxProvDenied card (SSA-02): a role that does not hold a surface gets an explanation,
// not a broken page. The surface's controls are absent, never rendered disabled.
export default function RoleDenied({ surface, body }: { surface: string; body?: string }) {
  return (
    <EmptyState
      icon="shield"
      title={`${surface} is not part of this role`}
      hint={
        body ??
        "SSA-02 · Navigation and actions follow your platform role. What you don't hold is absent, not disabled."
      }
    />
  );
}

export function ReadOnlyNote() {
  return (
    <p role="note" style={{ margin: "0 0 var(--nb-space-16)", fontSize: 12, color: "var(--nb-text-secondary)" }}>
      Read-only role — no write actions on this surface.
    </p>
  );
}
