export const a = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR" });
export const b = (n: number) => n.toLocaleString("en", { minimumFractionDigits: 2 });
export const c = (total: number) => total.toFixed(2);
