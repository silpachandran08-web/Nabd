import { ApiError, errorText } from "../../lib/api";

// S7-B02: a 403 carries the server's reason (e.g. vitals-correction-forbidden); generic only when it has none.
export const failText = (e: unknown, fallback: string) =>
  e instanceof ApiError && e.status === 403 ? e.detail || "Your role isn't allowed to do this." : errorText(e, fallback);
