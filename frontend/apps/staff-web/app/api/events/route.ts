// FEF-020: streaming proxy to Core GET /v1/events (logic and tests in lib/bffEvents.ts).
import { events } from "../../lib/bffEvents.ts";

export const dynamic = "force-dynamic";
export const GET = (req: Request) => events(req);
