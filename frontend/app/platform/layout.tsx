import { PlatformNav } from "./PlatformNav";

export default function PlatformLayout({ children }: LayoutProps<"/platform">) {
  return (
    <>
      <PlatformNav />
      {children}
    </>
  );
}
