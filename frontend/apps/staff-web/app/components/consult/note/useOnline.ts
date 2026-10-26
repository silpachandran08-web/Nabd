"use client";

import { useEffect, useState } from "react";

/** Browser online/offline, as the note editor sees it (starts true, corrected after mount). */
export function useOnline(): boolean {
  const [online, setOnline] = useState(true);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setOnline(navigator.onLine);
    const on = () => setOnline(true);
    const off = () => setOnline(false);
    window.addEventListener("online", on);
    window.addEventListener("offline", off);
    return () => { window.removeEventListener("online", on); window.removeEventListener("offline", off); };
  }, []);
  return online;
}
