// FEF-013: pregnancy prompt band, payment method labels and saved-set helpers.
import { expect, test } from "vitest";
import { isKsa, itemLine, lastUsedLabel, medCountLabel, paymentMethodLabel, PAYMENT_METHODS, pregnancyPromptRelevant, toWriteItem, type RxSetItem } from "../src/index.ts";

test("pregnancy prompt: female, 12 to 55 inclusive (configurable default awaiting clinical sign-off)", () => {
  expect(pregnancyPromptRelevant("Female", 12)).toBe(true);
  expect(pregnancyPromptRelevant("female", 55)).toBe(true);
  expect(pregnancyPromptRelevant("female", 11)).toBe(false);
  expect(pregnancyPromptRelevant("female", 56)).toBe(false);
  expect(pregnancyPromptRelevant("male", 30)).toBe(false);
  expect(pregnancyPromptRelevant(undefined, 30)).toBe(false);
});

test("payment labels are region-aware for UPI only", () => {
  expect(PAYMENT_METHODS).toEqual(["cash", "card", "upi", "other"]);
  expect(isKsa("KSA")).toBe(true);
  expect(isKsa(null)).toBe(false);
  expect(paymentMethodLabel("upi")).toBe("UPI");
  expect(paymentMethodLabel("upi", "KSA")).toBe("Mada & digital");
  expect(paymentMethodLabel("cash")).toBe("Cash");
  expect(paymentMethodLabel("card")).toBe("Card");
  expect(paymentMethodLabel("other")).toBe("Other");
  expect(paymentMethodLabel("cheque")).toBe("cheque");
});

test("saved sets: write items carry pad fields only, trimmed, blanks as null", () => {
  expect(toWriteItem({ drugName: " Amoxicillin ", dosage: " 500 mg ", sigPattern: "  ", quantity: 10 })).toEqual({
    drugName: "Amoxicillin", dosage: "500 mg", frequency: "", duration: "", instructions: "",
    brandName: null, sigPattern: null, route: null, quantity: 10, quantityUnit: null,
  });
  expect(medCountLabel(1)).toBe("1 med");
  expect(medCountLabel(3)).toBe("3 meds");
  expect(lastUsedLabel(null)).toBe("Never used");
  expect(lastUsedLabel("bad")).toBe("Never used");
  expect(lastUsedLabel("2026-10-01T00:00:00Z")).toBe("Last used 1 Oct 2026");
  const item = { drugName: "Paracetamol", brandName: null } as RxSetItem;
  expect(itemLine(item)).toBe("Paracetamol");
  expect(itemLine({ ...item, brandName: "Crocin" })).toBe("Paracetamol (Crocin)");
});
