"use client";

import { Suspense, useEffect, useState } from "react";
import ClinicNav from "./ClinicNav";
import TopBar from "./TopBar";

const COLLAPSE_KEY = "nabd_sidebar_collapsed";

// Owns the one piece of state ClinicNav and TopBar both need (the sidebar collapse toggle
// lives in the top bar, the sidebar it controls is a sibling) — a per-viewer UI preference, so
// localStorage is the right place for it, not anything server-synced.
export default function AppShell({ children }: { children: React.ReactNode }) {
  const [collapsed, setCollapsed] = useState(false);

  useEffect(() => {
    try {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setCollapsed(localStorage.getItem(COLLAPSE_KEY) === "1");
    } catch {
      // Private browsing / blocked storage — default (expanded) is fine.
    }
  }, []);

  function toggleCollapsed() {
    setCollapsed((prev) => {
      const next = !prev;
      try {
        localStorage.setItem(COLLAPSE_KEY, next ? "1" : "0");
      } catch {
        // Nothing to persist to — the toggle still works for this page view.
      }
      return next;
    });
  }

  return (
    <div className="appShell">
      {/* ClinicNav reads useSearchParams (nursing's deep-linked sidebar items), which requires
          a Suspense boundary or every other page's static prerender breaks. */}
      <Suspense fallback={null}>
        <ClinicNav collapsed={collapsed} />
      </Suspense>
      <div className="appShellContent">
        <TopBar collapsed={collapsed} onToggleCollapse={toggleCollapsed} />
        <div className="appShellBody">{children}</div>
      </div>
    </div>
  );
}
