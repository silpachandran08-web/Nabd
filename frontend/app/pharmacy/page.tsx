"use client";

import { useCallback } from "react";
import { useRouter } from "next/navigation";
import PharmacyPanel from "../components/setup/PharmacyPanel";
import AccessGate from "../components/ui/AccessGate";
import setupStyles from "../setup/setup.module.css";
import { apiFetch, goLogin } from "../lib/apiClient";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

// The pharmacist's own home (PHA-002): the Pharmacy panel on its own route, fed only by /pharmacy/*,
// so a pharmacy-only role never needs Setup access to reach the dispensing queue. The nav entry
// (ClinicNav, pharmacy:view) and the landing rule (lib/session.ts) point here; the Setup tab
// renders the same panel for setup:view holders who also hold pharmacy:view.
export default function PharmacyPage() {
  return (
    <AccessGate permission="pharmacy:view" surface="Pharmacy">
      <PharmacyPageInner />
    </AccessGate>
  );
}

function PharmacyPageInner() {
  const router = useRouter();

  const authedFetch = useCallback(async (path: string, init?: RequestInit) => {
    const token = localStorage.getItem("nabd_access_token");
    if (!token) {
      goLogin();
      return null;
    }
    const res = await apiFetch(path, {
      ...init,
      headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    });
    if (res.status === 401) {
      localStorage.removeItem("nabd_access_token");
      goLogin();
      return null;
    }
    return res;
  }, [router]);

  return (
    <main className={setupStyles.page}>
      <h1 className={setupStyles.title}>Pharmacy</h1>
      <p className={setupStyles.subtitle}>Dispensing queue, item master and stock for this clinic.</p>
      <PharmacyPanel authedFetch={authedFetch} />
    </main>
  );
}
