// FEF-047: "Rupees One Thousand Two Hundred Thirty Four and Paise Fifty Only" (Indian lakh/crore grouping), from integer minor units.
const ONES = ["", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"];
const TENS = ["", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"];

function below1000(n: number): string {
  const h = Math.floor(n / 100), r = n % 100;
  const rest = r < 20 ? ONES[r] : `${TENS[Math.floor(r / 10)]}${r % 10 ? " " + ONES[r % 10] : ""}`;
  return [h ? `${ONES[h]} Hundred` : "", rest].filter(Boolean).join(" ");
}

/** Whole number in words, Indian system (thousand, lakh, crore). Beyond 99,99,99,999 the crore part keeps counting ("One Hundred Crore"). */
export function integerInWords(n: bigint | number): string {
  let v = BigInt(n);
  if (v < BigInt(0)) throw new RangeError("amount in words needs a non-negative number");
  if (v === BigInt(0)) return "Zero";
  const parts: string[] = [];
  const take = (div: bigint, name: string) => {
    const q = v / div;
    v %= div;
    if (q > BigInt(0)) parts.push(`${q >= BigInt(100) ? integerInWords(q) : below1000(Number(q))} ${name}`);
  };
  take(BigInt(10_000_000), "Crore");
  take(BigInt(100_000), "Lakh");
  take(BigInt(1000), "Thousand");
  if (v > BigInt(0)) parts.push(below1000(Number(v)));
  return parts.join(" ");
}

/** Minor units (paise) to words; the unit names are passed in, nothing is region-specific here. */
export function amountInWords(minor: bigint | number, names: { major: string; minor: string } = { major: "Rupees", minor: "Paise" }): string {
  const m = BigInt(minor);
  const whole = m / BigInt(100), frac = Number(m % BigInt(100));
  const main = `${names.major} ${integerInWords(whole)}`;
  return `${main}${frac ? ` and ${names.minor} ${integerInWords(frac)}` : ""} Only`;
}
