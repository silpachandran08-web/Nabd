// FEF-009: authenticated same-origin proxy to Core (logic and tests in lib/bff.ts).
import { corePathOf, proxy } from "../../../lib/bff.ts";

export const dynamic = "force-dynamic";
const handle = (req: Request) => proxy(req, corePathOf(req, "/api/v1"));
export { handle as GET, handle as POST, handle as PUT, handle as PATCH, handle as DELETE };
