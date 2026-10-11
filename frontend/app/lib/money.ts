// One money formatter for the whole app (DESIGN.md §10): en-IN grouping for INR, trailing symbol
// for SAR. Amounts arrive as numbers in major units. Falls back to the plain code if Intl rejects it.
export function formatMoney(amount: number | null | undefined, currency = "INR"): string {
  if (amount === null || amount === undefined || Number.isNaN(amount)) return "—";
  try {
    if (currency === "SAR") return `${new Intl.NumberFormat("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount)} ﷼`;
    return new Intl.NumberFormat(currency === "INR" ? "en-IN" : "en-US", { style: "currency", currency, maximumFractionDigits: currency === "INR" ? 0 : 2 }).format(amount);
  } catch {
    return `${currency} ${amount}`;
  }
}
