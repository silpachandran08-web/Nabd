// CLN-018: footer text for the licensed drug knowledge base (pure, unit-tested).
export type DrugKbSource = { configured?: boolean; source?: string | null; dataMonth?: string | null };

const MONTH = /^(\d{4})-(0[1-9]|1[0-2])$/;
const NAMES = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];

/** "Drug data: <source> <Month YYYY>", or null when no KB is configured or either part is missing or malformed. */
export function attributionLine(d: DrugKbSource | null | undefined): string | null {
  const m = d && d.configured && d.source?.trim() && d.dataMonth ? MONTH.exec(d.dataMonth) : null;
  return d && m ? `Drug data: ${d.source!.trim()} ${NAMES[Number(m[2]) - 1]} ${m[1]}` : null;
}
