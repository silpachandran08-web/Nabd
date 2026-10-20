// FEF-009: revoke the session family at Core and clear the three session cookies.
import { logout } from "../../../lib/bff.ts";

export const dynamic = "force-dynamic";
export const POST = (req: Request) => logout(req);
