import PlainFrame from "../PlainFrame";

// FEF-029: owner routes have their own identity or no session yet: no clinic nav, top bar, idle lock or live stream.
export default function OwnerLayout({ children }: { children: React.ReactNode }) {
  return <PlainFrame>{children}</PlainFrame>;
}
