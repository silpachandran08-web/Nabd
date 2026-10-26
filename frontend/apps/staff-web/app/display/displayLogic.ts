// ACC-023: pure logic of the waiting-room display. No React, no fetch, no imports: the page must stay light for cheap TV hardware.
export type LobbyDoctor = { name: string; nowServing?: number | null; next: number[]; delayMinutes?: number | null };
export type Projection = { v: number; languages: string[]; doctors: LobbyDoctor[] };

export const STALE_AFTER_MS = 60_000;
export const LOST_AFTER_MS = 180_000;

export type Liveness = "live" | "stale" | "lost";

/** No frame for 60 s = stale, for 180 s = connection lost. The server resends every 15 s even when nothing changes. */
export function liveness(sinceLastFrameMs: number): Liveness {
  return sinceLastFrameMs >= LOST_AFTER_MS ? "lost" : sinceLastFrameMs >= STALE_AFTER_MS ? "stale" : "live";
}

/** Splits a text/event-stream buffer into complete frames; the rest stays in `rest`. */
export function parseSse(buffer: string): { frames: { event: string; data: string }[]; rest: string } {
  const parts = buffer.replace(/\r\n/g, "\n").split("\n\n");
  const rest = parts.pop() ?? "";
  const frames: { event: string; data: string }[] = [];
  for (const p of parts) {
    let event = "message";
    const data: string[] = [];
    for (const line of p.split("\n")) {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      else if (line.startsWith("data:")) data.push(line.slice(5).trim());
    }
    if (data.length) frames.push({ event, data: data.join("\n") });
  }
  return { frames, rest };
}

export const isRtl = (lang: string): boolean => lang === "ar" || lang === "he" || lang === "fa" || lang === "ur";

type Words = { now: string; next: string; late: (n: number) => string; stale: string; lost: string; none: string; pairTitle: string; pairHint: string; pair: string; wrong: string; locked: string };

/** Fixed phrases of the screen. Only English, Hindi and Arabic exist: the regions served are IN (en+hi) and the Gulf (en+ar). */
export const WORDS: Record<string, Words> = {
  en: { now: "Now serving", next: "Next", late: (n) => `Running about ${n} min late`, stale: "Not updated recently", lost: "Connection lost", none: "No doctors to show", pairTitle: "Pair this screen", pairHint: "Enter the 6-digit code shown in Waiting-room display on the front desk", pair: "Pair", wrong: "That code is wrong or has expired", locked: "Too many wrong codes. Try again in 15 minutes" },
  hi: { now: "अभी देखे जा रहे हैं", next: "अगले", late: (n) => `लगभग ${n} मिनट की देरी`, stale: "हाल में अपडेट नहीं हुआ", lost: "कनेक्शन टूट गया", none: "दिखाने के लिए कोई डॉक्टर नहीं", pairTitle: "इस स्क्रीन को जोड़ें", pairHint: "फ्रंट डेस्क पर दिखाया गया 6 अंकों का कोड डालें", pair: "जोड़ें", wrong: "कोड गलत है या समाप्त हो गया", locked: "बहुत ज़्यादा गलत कोड। 15 मिनट बाद कोशिश करें" },
  ar: { now: "الآن", next: "التالي", late: (n) => `تأخير حوالي ${n} دقيقة`, stale: "لم يتم التحديث مؤخرا", lost: "انقطع الاتصال", none: "لا يوجد أطباء للعرض", pairTitle: "اقرن هذه الشاشة", pairHint: "أدخل الرمز المكون من 6 أرقام من مكتب الاستقبال", pair: "اقتران", wrong: "الرمز خاطئ أو منتهي", locked: "رموز خاطئة كثيرة. حاول بعد 15 دقيقة" },
};

export const wordsFor = (lang: string): Words => WORDS[lang] ?? WORDS.en;
export const tokenText = (n: number): string => `T-${n}`;
