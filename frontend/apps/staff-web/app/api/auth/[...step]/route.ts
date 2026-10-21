// FEF-009: /api/auth/* -> Core /auth/* (login, MFA, OTP, PIN change, unlock...); token pairs become cookies.
import { corePathOf, proxy, refreshStep } from "../../../lib/bff.ts";

export const dynamic = "force-dynamic";
const handle = (req: Request) => {
  const path = corePathOf(req, "/api");
  return path === "/auth/refresh" ? refreshStep(req) : proxy(req, path, undefined, true);
};
export { handle as GET, handle as POST, handle as PUT, handle as PATCH, handle as DELETE };
