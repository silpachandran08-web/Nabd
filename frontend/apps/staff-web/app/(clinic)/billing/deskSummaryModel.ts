// ADM-044: the desk summary's pure parts, unit-tested in tests/unit/s17-adm044-desk-summary.test.mjs.

/** An unbilled visit opens checkout for that visit. */
export const checkoutHref = (queueEntryId: string) => `/checkout/${encodeURIComponent(queueEntryId)}`;

/** Day close opens with the counted cash pre-filled to the expected cash, unless the receptionist already typed a count. */
export const prefillCounted = (counted: string, expectedCash: number) => (counted.trim() === "" ? String(expectedCash) : counted);

/** Expected cash = the day's net cash, the same figure the server closes against. */
export const expectedCashOf = (methods: { method?: string; amount?: number }[]) => Number(methods.find((m) => m.method === "cash")?.amount ?? 0);

export const collectedTotal = (methods: { amount?: number }[]) => methods.reduce((sum, m) => sum + Number(m.amount ?? 0), 0);
