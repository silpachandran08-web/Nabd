"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { Skeleton } from "../../components/ui/StateViews";
import ClassicReports from "./ClassicReports";
import ReportsLibrary from "./ReportsLibrary";

// ADM-047: the reports library is the Reports screen (/reports?report=<key>&from&to&branch). The earlier single page with day close
// and package reminders stays reachable at ?view=classic until those cards move into the library.
export default function ReportsPage() {
  return <Suspense fallback={<main style={{ padding: 32 }}><Skeleton rows={6} label="Loading reports" /></main>}><Switch /></Suspense>;
}

function Switch() {
  return useSearchParams().get("view") === "classic" ? <ClassicReports /> : <ReportsLibrary />;
}
