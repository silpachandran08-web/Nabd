import PlatformShell from "./PlatformShell";

export default function PlatformLayout({ children }: LayoutProps<"/platform">) {
  return <PlatformShell>{children}</PlatformShell>;
}
