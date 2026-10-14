import type { CSSProperties } from "react";

// One monoline family, stroke 1.75 (DESIGN.md §3). Paths are 24x24, drawn by hand — no icon
// package dependency. Icons are decorative by default (aria-hidden); pass `label` to expose one.
const PATHS = {
  sun: "M12 16a4 4 0 100-8 4 4 0 000 8zM12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4",
  home: "M3 11l9-8 9 8M5 10v10h5v-6h4v6h5V10",
  calendar: "M4 6h16v14H4zM4 10h16M8 3v4M16 3v4",
  users: "M9 11a3.5 3.5 0 100-7 3.5 3.5 0 000 7zM2.5 20c0-3.3 2.9-6 6.5-6s6.5 2.7 6.5 6M16 4.3a3.5 3.5 0 010 6.4M18 14.4c2 .8 3.5 2.7 3.5 5.6",
  stethoscope: "M6 3v6a4 4 0 008 0V3M10 13v2a5 5 0 0010 0v-2M20 11a2 2 0 100 4 2 2 0 000-4z",
  receipt: "M6 3h12v18l-3-2-3 2-3-2-3 2zM9 8h6M9 12h6",
  package: "M3 7.5L12 3l9 4.5v9L12 21l-9-4.5zM3 7.5l9 4.5 9-4.5M12 12v9",
  shield: "M12 3l8 3v6c0 4.5-3.2 8-8 9-4.8-1-8-4.5-8-9V6z",
  chart: "M4 20V4M4 20h16M8 16v-5M12 16V8M16 16v-8",
  settings: "M12 15a3 3 0 100-6 3 3 0 000 6zM19 12l2-1.2-2-3.5-2.3.7a7 7 0 00-1.6-.9L14.5 5h-4l-.6 2.1a7 7 0 00-1.6.9L6 7.3 4 10.8 6 12l-2 1.2 2 3.5 2.3-.7a7 7 0 001.6.9l.6 2.1h4l.6-2.1a7 7 0 001.6-.9l2.3.7 2-3.5z",
  user: "M12 12a4 4 0 100-8 4 4 0 000 8zM4 21c0-4 3.6-7 8-7s8 3 8 7",
  pulse: "M3 12h4l2-6 4 12 2-6h6",
  syringe: "M18 3l3 3M16 5l3 3M14 7l3 3-8 8-4 1 1-4zM4 20l3-3",
  clock: "M12 21a9 9 0 100-18 9 9 0 000 18zM12 7v5l3 2",
  list: "M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01",
  more: "M5 12h.01M12 12h.01M19 12h.01",
  check: "M5 12.5l4.5 4.5L19 7.5",
  alert: "M12 4l9 16H3zM12 10v4M12 17h.01",
  info: "M12 21a9 9 0 100-18 9 9 0 000 18zM12 11v5M12 8h.01",
  x: "M6 6l12 12M18 6L6 18",
  refresh: "M20 11a8 8 0 00-14-4L4 9M4 4v5h5M4 13a8 8 0 0014 4l2-2M20 20v-5h-5",
  inbox: "M3 13l3-8h12l3 8v6H3zM3 13h5l1 3h6l1-3h5",
  plus: "M12 5v14M5 12h14",
  building: "M5 21V4h9v17M14 9h5v12M9 8h1M9 12h1M9 16h1M3 21h18",
  chevron: "M9 6l6 6-6 6",
  login: "M10 17l5-5-5-5M15 12H3M14 4h5a2 2 0 012 2v12a2 2 0 01-2 2h-5",
  bolt: "M13 3L5 13.5h6L10 21l8-10.5h-6z",
  sync: "M4 12a8 8 0 0113.7-5.6L20 8M20 4v4h-4M20 12a8 8 0 01-13.7 5.6L4 16M4 20v-4h4",
  // Platform console nav glyphs, traced from the wireframe's <symbol> set (i-globe, i-wallet, ...).
  globe: "M12 20.5a8.5 8.5 0 100-17 8.5 8.5 0 000 17zM3.5 12h17M12 3.5c2.3 2.4 3.5 5.3 3.5 8.5S14.3 18.6 12 20.5c-2.3-1.9-3.5-5.3-3.5-8.5S9.7 5.9 12 3.5z",
  wallet: "M6.5 6h11a3 3 0 013 3v7a3 3 0 01-3 3h-11a3 3 0 01-3-3V9a3 3 0 013-3zM3.5 10.5h17M16 14.7h1.6",
  sparkle: "M10 3l1.7 4.6L16 9.2l-4.3 1.6L10 15.4 8.3 10.8 4 9.2l4.3-1.6zM17.8 14.4l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z",
  lifebuoy: "M12 20.5a8.5 8.5 0 100-17 8.5 8.5 0 000 17zM12 15.5a3.5 3.5 0 100-7 3.5 3.5 0 000 7zM6 6l3.5 3.5M18 6l-3.5 3.5M6 18l3.5-3.5M18 18l-3.5-3.5",
  shieldUser: "M12 3l7.5 3v6c0 4.4-3.1 8.2-7.5 9.4C7.6 20.2 4.5 16.4 4.5 12V6zM12 12.6a2.2 2.2 0 100-4.4 2.2 2.2 0 000 4.4zM8.4 17c.5-1.9 1.9-2.9 3.6-2.9s3.1 1 3.6 2.9",
  scroll: "M6.5 4h11v14a2.5 2.5 0 002.5 2.5H8A2.5 2.5 0 015.5 18V5a1 1 0 011-1zM9 8.5h6M9 12.5h6",
  plusSquare: "M7.5 3.5h9a4 4 0 014 4v9a4 4 0 01-4 4h-9a4 4 0 01-4-4v-9a4 4 0 014-4zM12 8.5v7M8.5 12h7",
} as const;

export type IconName = keyof typeof PATHS;

export default function Icon({ name, size = 18, label, style }: { name: IconName; size?: number; label?: string; style?: CSSProperties }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden={label ? undefined : true}
      role={label ? "img" : undefined}
      aria-label={label}
      style={{ flex: "none", ...style }}
    >
      <path d={PATHS[name]} />
    </svg>
  );
}
