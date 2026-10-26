import { notFound } from "next/navigation";

// FEF-029: an unknown URL under this group renders the group's not-found.tsx inside the group's layout (the root not-found has no shell).
export default function UnknownRoute() {
  notFound();
}
