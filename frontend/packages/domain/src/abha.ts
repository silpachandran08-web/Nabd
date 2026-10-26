// IDC-017 pure helpers. ABHA number = 14 digits (shown 2-4-4-4); ABHA address = name@abdm or name@sbx.
const DIGITS14 = /^\d{14}$/;
const ADDRESS = /^[a-z0-9._-]{4,}@(abdm|sbx)$/i;

export const stripAbhaNumber = (s: string): string => s.replace(/[\s-]/g, "");

export function isAbhaNumber(s: string): boolean { return DIGITS14.test(stripAbhaNumber(s)); }
export function isAbhaAddress(s: string): boolean { return ADDRESS.test(s.trim()); }
export function isAbhaId(s: string): boolean { return isAbhaNumber(s) || isAbhaAddress(s); }

/** Display only the last 4 digits; the full number is never shown after verification. */
export const maskAbha = (last4: string): string => `XX-XXXX-XXXX-${last4}`;

export interface AbhaQrPrefill { name?: string; dob?: string; gender?: "female" | "male" | "other"; phone?: string; abhaNumber?: string; abhaAddress?: string }

const GENDER: Record<string, AbhaQrPrefill["gender"]> = { f: "female", female: "female", m: "male", male: "male", o: "other", other: "other", t: "other" };

/** Parse the JSON in an ABHA QR (keys as ABDM prints them: name, dob DD-MM-YYYY, gender, mobile, hidn, hid). Returns null if unreadable. */
export function parseAbhaQr(payload: string): AbhaQrPrefill | null {
  let j: Record<string, unknown>;
  try { j = JSON.parse(payload) as Record<string, unknown>; } catch { return null; }
  if (!j || typeof j !== "object") return null;
  const str = (k: string) => (typeof j[k] === "string" ? (j[k] as string).trim() : "");
  const out: AbhaQrPrefill = {};
  const name = str("name") || [str("firstName"), str("middleName"), str("lastName")].filter(Boolean).join(" ");
  if (name) out.name = name;
  const dob = str("dob").match(/^(\d{2})-(\d{2})-(\d{4})$/);
  if (dob) out.dob = `${dob[3]}-${dob[2]}-${dob[1]}`;
  else if (/^\d{4}-\d{2}-\d{2}$/.test(str("dob"))) out.dob = str("dob");
  const g = GENDER[str("gender").toLowerCase()];
  if (g) out.gender = g;
  const mobile = str("mobile") || str("phone");
  if (/^\d{10}$/.test(mobile)) out.phone = `+91${mobile}`;
  else if (mobile) out.phone = mobile;
  const hidn = stripAbhaNumber(str("hidn") || str("abhaNumber"));
  if (DIGITS14.test(hidn)) out.abhaNumber = hidn;
  const hid = str("hid") || str("abhaAddress");
  if (hid && ADDRESS.test(hid)) out.abhaAddress = hid;
  return Object.keys(out).length ? out : null;
}
