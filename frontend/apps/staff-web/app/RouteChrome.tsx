"use client";

import { useEffect, useState } from "react";
import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";
import { getIdentity } from "./lib/session";
import { pageName, routeTitle } from "./lib/routeTitle";

// FEF-027: 'Skip to content' (first Tab stop, moves focus to <main>) and the per-route document.title,
// announced through a polite live region so screen readers hear the page change.
export default function RouteChrome() {
  const t = useTranslations("shell");
  const pathname = usePathname();
  const [announce, setAnnounce] = useState("");
  useEffect(() => {
    const want = routeTitle(pathname, getIdentity()?.tenantName);
    document.title = want;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setAnnounce(pageName(pathname));
    // Next re-applies its static metadata title ("Nabd") after hydration and on head sync; put ours back.
    const obs = new MutationObserver(() => { if (document.title !== want) document.title = want; });
    obs.observe(document.head, { childList: true, subtree: true, characterData: true });
    return () => obs.disconnect();
  }, [pathname]);

  function skip(e: React.MouseEvent) {
    e.preventDefault();
    const target = document.querySelector<HTMLElement>("main") ?? document.getElementById("app-body");
    if (!target) return;
    target.tabIndex = -1;
    target.focus();
  }
  return (
    <>
      <a href="#app-body" className="skipLink" onClick={skip}>{t("chrome.skip")}</a>
      <div role="status" aria-live="polite" className="srOnly">{announce}</div>
    </>
  );
}
