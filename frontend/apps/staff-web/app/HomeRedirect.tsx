"use client";
// FEF-029: "/" sends a signed-in user to their landing page (same rule as the login page), everyone else to /login.
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { decodeAccessToken, landingPathFor } from "./lib/session";

export default function HomeRedirect() {
  const router = useRouter();
  useEffect(() => {
    const token = localStorage.getItem("nabd_access_token");
    const claims = token ? decodeAccessToken(token) : null;
    router.replace(claims ? landingPathFor(claims.permissions ?? [], claims.owner === true) : "/login");
  }, [router]);
  return null;
}
