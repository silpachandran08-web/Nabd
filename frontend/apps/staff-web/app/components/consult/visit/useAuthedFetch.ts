"use client";

import { useCallback } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { dropVisitReads } from "../../../lib/doctorQueries";
import { responseFetch } from "../../../lib/responseFetch";
import type { AuthedFetch } from "../shared";

// FEF-016: every consult call goes through the shared api client (token, refresh, timeout, retry, Idempotency-Key).
export function useAuthedFetch(): AuthedFetch {
  const qc = useQueryClient();
  return useCallback<AuthedFetch>(async (path, init) => {
    const res = await responseFetch(path, init);
    if (init?.method && init.method !== "GET" && path.startsWith("/clinical/")) dropVisitReads(qc); // a queue move leaves the note and Rx untouched
    return res;
  }, [qc]);
}
