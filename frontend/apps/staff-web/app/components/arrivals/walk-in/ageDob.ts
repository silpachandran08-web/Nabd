// ACC-031: the minimum dataset takes "age or date of birth". The API stores a date of birth only, so an age becomes 1 January of
// the year that makes the patient exactly that age today (shown in the DOB field, editable). Pure.
export function dobFromAge(ageText: string, today: string): string {
  const t = ageText.trim();
  if (!/^\d{1,3}$/.test(t)) return "";
  const n = Number(t);
  const year = Number(today.slice(0, 4)) - n;
  return n > 120 || year < 1 ? "" : `${String(year).padStart(4, "0")}-01-01`;
}
