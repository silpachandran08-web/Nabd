"use client";

import { useSearchParams } from "next/navigation";
import LabCollection from "../../../components/lab/LabCollection";

// PHL-045: collection screen. Orders are passed through the queueEntryId query param (later: order id from route).
export default function Page() {
  const params = useSearchParams();
  const orderId = params.get("orderId") || "";
  
  if (!orderId) {
    return <div>Order ID required</div>;
  }

  return <LabCollection orderId={orderId} />;
}
