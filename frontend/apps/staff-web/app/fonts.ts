// FEF-036: script fonts. Poppins (Latin) stays in layout.tsx. Each family is an already-subset file for its own script
// (@fontsource), declared with its unicode-range, so a page downloads one only when it shows that script (Hindi UI: one
// ~60 KB file; English UI: none). display: swap shows system text first. Arabic is wired but class-applied only for ar.
import localFont from "next/font/local";


export const notoDevanagari = localFont({
  display: "swap", preload: false, fallback: ["system-ui", "sans-serif"], variable: "--font-deva", weight: "400 700",
  src: "../node_modules/@fontsource-variable/noto-sans-devanagari/files/noto-sans-devanagari-devanagari-wght-normal.woff2",
  declarations: [{ prop: "unicode-range", value: "U+0900-097F,U+1CD0-1CF9,U+200C-200D,U+20A8,U+20B9,U+25CC,U+A830-A839,U+A8E0-A8FF" }],
});
export const notoKannada = localFont({
  display: "swap", preload: false, fallback: ["system-ui", "sans-serif"], variable: "--font-knda",
  src: [
    { path: "../node_modules/@fontsource/noto-sans-kannada/files/noto-sans-kannada-kannada-400-normal.woff2", weight: "400" },
    { path: "../node_modules/@fontsource/noto-sans-kannada/files/noto-sans-kannada-kannada-600-normal.woff2", weight: "600" },
  ],
  declarations: [{ prop: "unicode-range", value: "U+0C80-0CF3,U+1CD0,U+1CD2-1CD3,U+1CDA,U+1CF2,U+1CF4,U+200C-200D,U+20B9,U+25CC" }],
});
export const notoTelugu = localFont({
  display: "swap", preload: false, fallback: ["system-ui", "sans-serif"], variable: "--font-telu",
  src: [
    { path: "../node_modules/@fontsource/noto-sans-telugu/files/noto-sans-telugu-telugu-400-normal.woff2", weight: "400" },
    { path: "../node_modules/@fontsource/noto-sans-telugu/files/noto-sans-telugu-telugu-600-normal.woff2", weight: "600" },
  ],
  declarations: [{ prop: "unicode-range", value: "U+0C00-0C7F,U+1CDA,U+1CF2,U+200C-200D,U+20B9,U+25CC" }],
});
export const plexArabic = localFont({
  display: "swap", preload: false, fallback: ["system-ui", "sans-serif"], variable: "--font-ar",
  src: [
    { path: "../node_modules/@fontsource/ibm-plex-sans-arabic/files/ibm-plex-sans-arabic-arabic-400-normal.woff2", weight: "400" },
    { path: "../node_modules/@fontsource/ibm-plex-sans-arabic/files/ibm-plex-sans-arabic-arabic-600-normal.woff2", weight: "600" },
  ],
  declarations: [{ prop: "unicode-range", value: "U+0600-06FF,U+0750-077F,U+0870-088E,U+0890-0891,U+0897-08E1,U+08E3-08FF,U+200C-200E,U+2010-2011,U+204F,U+2E41,U+FB50-FDFF,U+FE70-FE74,U+FE76-FEFC" }],
});

/** CSS variable classes for the page. Devanagari/Kannada/Telugu are always declared (cost nothing until used); Arabic only for ar. */
export const scriptFontClasses = (locale: string) =>
  [notoDevanagari.variable, notoKannada.variable, notoTelugu.variable, locale === "ar" ? plexArabic.variable : ""].filter(Boolean).join(" ");
