"use client";

import { useEffect, useState } from "react";
import PharmacyPanel from "../../../components/setup/PharmacyPanel";
import { can } from "../../../lib/permissions";

// The tab (and this panel) only exist for a role with pharmacy:view; PharmacyPanel reads only /pharmacy/*.
export default function PharmacyTab() {
  const [canPharmacy, setCanPharmacy] = useState(false);
  useEffect(() => {
    void Promise.resolve().then(() => setCanPharmacy(can("pharmacy:view")));
  }, []);
  return canPharmacy ? <PharmacyPanel /> : null;
}
