// COM-013: plain-language reasons and the owner's attention banner for WhatsApp templates. Pure functions (unit-tested).

export type TemplateVersion = {
  id: string; version: number; status: string; rejectionReason: string | null; active: boolean; headerText: string; body: string; createdAt: string;
};

export type TemplateLike = {
  id: string; name: string; key: string; source: "catalogue" | "custom"; language: string; status: string; rejectionReason: string | null;
  category: string; requestedCategory: string; categoryChangedAt: string | null; pausedUntil: string | null;
  version: number; activeVersion: number; versions: TemplateVersion[];
};

export type ReasonHelp = { text: string; fix: string };

// Meta's rejection codes in plain words, each with what to change. Anything else is shown as Meta sent it.
const REASONS: Record<string, ReasonHelp> = {
  INCORRECT_CATEGORY: { text: "Meta thinks the category is wrong.", fix: "Offers and promotions must be Marketing; reminders and updates about a visit are Utility. Keep the wording to the visit itself." },
  INVALID_FORMAT: { text: "The wording or the {{n}} slots break Meta's formatting rules.", fix: "Don't start or end with a slot, don't put two slots side by side, and give a realistic example for each one." },
  ABUSIVE_CONTENT: { text: "Meta flagged the wording as against its commerce policy.", fix: "Remove threatening or pushy phrases and anything that reads like pressure to pay or act." },
  SCAM: { text: "Meta flagged the wording as possibly misleading.", fix: "Name the clinic clearly, avoid links you don't control and avoid promises of prizes or rewards." },
  TAG_CONTENT_MISMATCH: { text: "The wording doesn't match the chosen category.", fix: "Either change the wording to match the category or choose the category that fits the wording." },
  PROMOTIONAL: { text: "Meta found promotional wording in a Utility template.", fix: "Remove offers, discounts and calls to buy, or create it as a Marketing template." },
};

export function reasonHelp(code: string | null | undefined): ReasonHelp | null {
  if (!code) return null;
  return REASONS[code] ?? { text: code, fix: "Change the wording and submit it again; Meta reviews every new version." };
}

/** The newest version that is not the active one, when it is still in review or was rejected (the owner's open change). */
export function pendingChange(t: TemplateLike): TemplateVersion | null {
  const newer = t.versions.filter((v) => v.version > t.activeVersion).sort((a, b) => b.version - a.version)[0];
  return newer ?? null;
}

export const inReview = (status: string) => status === "submitting" || status === "queued" || status === "pending";

export type Alert = { id: string; tone: "warn" | "danger"; text: string };

/** What needs the owner: a rejected version (with the fix), a pause (sends are held), a disabled template, or Meta moving a
 * template to Marketing (sends now need marketing consent). */
export function attention(templates: TemplateLike[], label: (t: TemplateLike) => string, now = Date.now()): Alert[] {
  const out: Alert[] = [];
  for (const t of templates) {
    const name = label(t);
    const change = pendingChange(t);
    const rejected = change?.status === "rejected" ? change : t.status === "rejected" ? t.versions.find((v) => v.active) ?? null : null;
    if (rejected) {
      out.push({ id: t.id + ":rejected", tone: "danger", text: `${name}: Meta rejected the latest version. ${reasonHelp(rejected.rejectionReason)?.text ?? ""}`.trim() });
    }
    if (t.status === "paused") {
      const until = t.pausedUntil ? new Date(t.pausedUntil) : null;
      const left = until && until.getTime() > now ? ` until about ${until.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}` : "";
      out.push({ id: t.id + ":paused", tone: "warn", text: `${name}: Meta paused this template${left} because of patient feedback. Reminders wait and go out when it lifts; any that would arrive too late are not sent.` });
    }
    if (t.status === "disabled") {
      out.push({ id: t.id + ":disabled", tone: "danger", text: `${name}: Meta disabled this template after repeated low quality. Edit it to submit a new version.` });
    }
    if (t.categoryChangedAt && t.category === "MARKETING" && t.requestedCategory !== "MARKETING") {
      out.push({ id: t.id + ":category", tone: "warn", text: `${name}: Meta moved this template to Marketing. It now goes only to patients who agreed to marketing messages, and costs more per message.` });
    }
  }
  return out;
}
