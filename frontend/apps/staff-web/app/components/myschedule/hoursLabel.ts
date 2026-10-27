// ACC-005: pure so unit tests can import it without React.
/** "20:00–01:00 (+1)": an hours block that ends on the next calendar day shows the marker (ACC-005). */
export const hoursLabel = (h: { startTime: string; endTime: string; endsNextDay?: boolean }) => `${h.startTime.slice(0, 5)}–${h.endTime.slice(0, 5)}${h.endsNextDay ? " (+1)" : ""}`;
