// FEF-033: form schemas = the API's generated request schemas, narrowed with pick() to what the form collects, plus the few rules the
// OpenAPI cannot express (phone per market, a date that is not in the future, a reason when a discount is given). Messages are keys
// (see formValidation.ts). Pure: the clinic's country and today's date are arguments.
import { AppointmentWriteRequestSchema, CheckInRequestSchema, DiscountRequestSchema, PatientWriteRequestSchema } from "@nabd/api-client/generated";
import { normalizePhone } from "@nabd/i18n/format";

type Ctx = { addIssue: (i: { code: "custom"; message: string; path: string[] }) => void };
const blank = (v: unknown) => typeof v !== "string" || v.trim() === "";
const need = (ctx: Ctx, v: Record<string, unknown>, keys: string[]) => { for (const k of keys) if (blank(v[k])) ctx.addIssue({ code: "custom", message: "required", path: [k] }); };

/** Registration (walk-in / quick create): name, mobile (E.164 per market), date of birth, sex. */
export const registerSchema = (country: string | undefined, today: string) =>
  PatientWriteRequestSchema.pick({ name: true, phone: true, dob: true, gender: true }).superRefine((v, ctx) => {
    need(ctx, v, ["name", "phone", "dob", "gender"]);
    if (!blank(v.phone) && !normalizePhone(v.phone, country)) ctx.addIssue({ code: "custom", message: "phoneInvalid", path: ["phone"] });
    if (!blank(v.dob) && v.dob > today) ctx.addIssue({ code: "custom", message: "dobFuture", path: ["dob"] });
  });

/** Check-in of a known patient: the doctor to see. */
export const checkInSchema = CheckInRequestSchema.pick({ doctorId: true }).superRefine((v, ctx) => need(ctx, v, ["doctorId"]));

/** Booking: patient, doctor, a free slot. */
export const bookingSchema = AppointmentWriteRequestSchema.pick({ patientId: true, doctorId: true, startTime: true }).superRefine((v, ctx) => need(ctx, v, ["patientId", "doctorId", "startTime"]));

/** Checkout discount (only checked when one is typed): an amount within the subtotal and a reason. */
export const discountSchema = (subtotal: number) =>
  DiscountRequestSchema.pick({ value: true, reasonCode: true }).superRefine((v, ctx) => {
    if (blank(v.reasonCode)) ctx.addIssue({ code: "custom", message: "discountReason", path: ["reasonCode"] });
    if (v.value > subtotal) ctx.addIssue({ code: "custom", message: "discountTooBig", path: ["value"] });
  });

/** The stored phone: E.164 when valid, otherwise what was typed (the schema has already refused it). */
export const e164 = (raw: string, country: string | undefined) => normalizePhone(raw, country) ?? raw.trim();
