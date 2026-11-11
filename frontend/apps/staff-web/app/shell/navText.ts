// FEF-040: a nav item's label in the user's language. Each nav model (owner sections, counter shells, the clinic roles) has its
// own catalogue block in the shell namespace because ids repeat across models with different text ("reports", "setup").
import { COUNTER_ROLES, type NavItem, type RoleContext } from "./navRegistry";

type T = { (key: string): string; has(key: string): boolean };

export function navText(t: T, item: NavItem, role: RoleContext, field: "label" | "short" = "label"): string {
  const model = item.section && role === "owner" ? "ownerNav" : COUNTER_ROLES.includes(role) ? "counterNav" : "clinicNav";
  const key = `${model}.${item.id}.${field}`;
  return t.has(key) ? t(key) : item[field];
}
