// Lint fixture (FEF-012): MUST pass. Own folder and packages/* are allowed.
import type { Invoice } from "@nabd/api-client/types";
import { x } from "./bad-cross-feature";
export const y: [unknown, Invoice | undefined] = [x, undefined];
