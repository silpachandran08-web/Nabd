"use client";

import { createPortal } from "react-dom";
import ConfirmDialog from "../ConfirmDialog";

/** A confirm opened from the drawer renders beside it (not inside its scrim), as the old page did. */
export default function ConfirmPortal(props: React.ComponentProps<typeof ConfirmDialog>) {
  return createPortal(<ConfirmDialog {...props} />, document.body);
}
