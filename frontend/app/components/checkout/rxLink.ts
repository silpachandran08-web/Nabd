// PHL-001: prescription-only (isRx, the clinic's catalogue flag) lines must carry a signed prescription line id.
// Pure helpers shared by the counter sale and the visit checkout.

type Coded = { chargeCode: string; prescriptionItemId?: string };
type Prescribed = { prescriptionItemId: string; drugName: string; remainingQty: number };

/** Codes of Rx lines still waiting for a prescription link (Pay/Checkout stays off until this is empty). */
export function unlinkedRx(items: Coded[], charges: { code: string; isRx?: boolean }[]): string[] {
  const rx = new Set(charges.filter((c) => c.isRx).map((c) => c.code));
  return items.filter((i) => rx.has(i.chargeCode) && !i.prescriptionItemId).map((i) => i.chargeCode);
}

/** Visit checkout pre-link: the visit's signed line for this item, by name, that still has quantity left. */
export function autoLink(chargeName: string, prescribed: Prescribed[]): string | null {
  const name = chargeName.trim().toLowerCase();
  const hit = prescribed.find((p) => {
    const d = p.drugName.trim().toLowerCase();
    return p.remainingQty > 0 && d.length > 0 && (name.includes(d) || d.includes(name));
  });
  return hit ? hit.prescriptionItemId : null;
}

const RX_RULES: Record<string, string> = {
  "rx-required": "This item is prescription-only: link it to a signed prescription before billing.",
  "rx-not-signed": "The linked prescription is still a draft: the doctor must sign it first.",
  "rx-patient-mismatch": "The linked prescription belongs to a different patient.",
  "rx-quantity-exhausted": "The linked prescription line has no quantity left for this amount.",
};

/** Names the rule that refused the bill (the server detail adds the item code), or null for a non-Rx problem. */
export function rxErrorMessage(p: { type?: string; detail?: string }): string | null {
  const slug = p.type?.split("/").pop() ?? "";
  const rule = RX_RULES[slug];
  return rule ? (p.detail ? `${rule} ${p.detail}` : rule) : null;
}
