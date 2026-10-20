// FEF-009: current session claims for the shell (never a token).
import { session } from "../../lib/bff.ts";

export const dynamic = "force-dynamic";
export const GET = (req: Request) => session(req);
