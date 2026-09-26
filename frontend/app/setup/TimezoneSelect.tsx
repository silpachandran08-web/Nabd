"use client";

// The clinic's wall clock: working hours, "today's" queue and reports all follow it (backend ClinicClock).
const ZONES = [
  { value: "Asia/Kolkata", label: "India (Asia/Kolkata, UTC+5:30)" },
  { value: "Asia/Riyadh", label: "Saudi Arabia (Asia/Riyadh, UTC+3)" },
  { value: "UTC", label: "UTC" },
];

/** A select, not free text — the backend rejects anything that isn't a real IANA zone ("IST" isn't). */
export default function TimezoneSelect({ id, className, value, onChange }: {
  id?: string; className?: string; value: string; onChange: (tz: string) => void;
}) {
  const known = ZONES.some((z) => z.value === value);
  return (
    <select id={id} className={className} value={value} onChange={(e) => onChange(e.target.value)}>
      {!known && <option value={value}>{value || "Not set"} — choose a timezone</option>}
      {ZONES.map((z) => <option key={z.value} value={z.value}>{z.label}</option>)}
    </select>
  );
}
