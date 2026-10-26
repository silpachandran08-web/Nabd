// FEF-036.6: user-entered text in a mixed-direction page. <bdi dir="auto"> isolates a name or drug name so an Arabic
// name inside an English row (or the reverse) neither reorders its neighbours nor flips its own punctuation;
// phone numbers are always left-to-right digits, even in an RTL page.
import type { ReactNode } from "react";

export function Name({ children }: { children: ReactNode }) {
  return <bdi dir="auto">{children}</bdi>;
}

export function Phone({ children }: { children: string | null | undefined }) {
  return <bdi dir="ltr">{children || "—"}</bdi>;
}
