"use client";

import { useCallback } from "react";
import { useRouter } from "next/navigation";
import PharmacyPanel from "../components/setup/PharmacyPanel";
import { EmptyState } from "../components/ui/StateViews";
import { can } from "../lib/permissions";
import { useHasMounted } from "../lib/useHasMounted";
import setupStyles from "../setup/setup.module.css";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

// The pharmacist's own home (PHA-002): the Pharmacy panel on its own route, fed only by /pharmacy/*,
// so a pharmacy-only role never needs Setup access to reach the dispensing queue.
export default function PharmacyPage() {
  const router = useRouter();
  const mounted = useHasMounted();

  const authedFetch = useCallback(async (path: string, init?: RequestInit) => {
    const token = localStorage.getItem("nabd_access_token");
    if (!token) {
      router.replace("/login");
      return null;
    }
    const res = await fetch(`${API_BASE}${path}`, {
      ...init,
      headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    });
    if (res.status === 401) {
      localStorage.removeItem("nabd_access_token");
      router.replace("/login");
      return null;
    }
    return res;
  }, [router]);

  if (!mounted) return null;
  if (!can("pharmacy:view")) {
    return <EmptyState title="Pharmacy isn't available for your role" hint="Ask your clinic owner if you need access." icon="shield" />;
  }
  return (
    <main className={setupStyles.page}>
      <h1 className={setupStyles.title}>Pharmacy</h1>
      <PharmacyPanel authedFetch={authedFetch} />
    </main>
  );
}
