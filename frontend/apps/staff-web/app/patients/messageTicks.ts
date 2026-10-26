// COM-006: the delivery tick for a message status. Pure, so it is unit-tested without React.
/** One grey tick = sent, two grey = delivered, two blue = read, red ! = failed or blocked. */
export function tickOf(status: string): { glyph: string; label: string; tone: "grey" | "blue" | "red" } {
  switch (status) {
    case "sent": return { glyph: "✓", label: "Sent", tone: "grey" };
    case "delivered": return { glyph: "✓✓", label: "Delivered", tone: "grey" };
    case "read": return { glyph: "✓✓", label: "Read", tone: "blue" };
    case "failed": return { glyph: "!", label: "Failed", tone: "red" };
    case "blocked": return { glyph: "!", label: "Not sent", tone: "red" };
    case "cancelled": return { glyph: "–", label: "Cancelled", tone: "grey" };
    case "expired": return { glyph: "–", label: "Expired", tone: "grey" };
    default: return { glyph: "…", label: "Sending", tone: "grey" }; // scheduled / queued / sending
  }
}
