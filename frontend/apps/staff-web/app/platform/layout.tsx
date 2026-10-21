import { PlatformNav } from "./PlatformNav";
import styles from "./platform.module.css";

export default function PlatformLayout({ children }: LayoutProps<"/platform">) {
  return (
    <div className={styles.shell}>
      <PlatformNav />
      <div className={styles.shellContent}>{children}</div>
    </div>
  );
}
