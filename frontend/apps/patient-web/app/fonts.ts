// FEF-061: Noto Sans for the Indian scripts, same already-subset @fontsource files as staff-web (FEF-036), declared with unicode-range so
// a page downloads one only when it shows that script. Latin text uses the system font (no web font on a 3G first paint).
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

export const scriptFontClasses = [notoDevanagari.variable, notoKannada.variable, notoTelugu.variable].join(" ");
