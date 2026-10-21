// Lint fixture (FEF-012): MUST fail. packages/ui may not import the API client.
import { createClient } from "@nabd/api-client";
import type { Invoice } from "../../api-client/types.ts";
export const c: typeof createClient | Invoice | undefined = undefined;
