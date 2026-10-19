// FEF-049 page objects: one per screen the smoke journeys drive. Locators are role/label based, the same ones staff see.
import { APP } from './harness.mjs';

export class ArrivalsPage {
  constructor(page) { this.page = page; }
  async open() { await this.page.goto(`${APP}/arrivals`); await this.page.getByRole('button', { name: 'Walk-in & token' }).waitFor(); }
  async registerWalkIn({ name, phone, dob, gender = 'female' }, doctorName) {
    const p = this.page;
    await p.getByRole('button', { name: 'Walk-in & token' }).click();
    const d = p.getByRole('dialog', { name: 'Register walk-in' });
    await d.getByLabel('Patient').fill(name);
    await d.getByRole('button', { name: /No match — register a new patient/ }).click();
    await d.getByLabel('Name', { exact: true }).fill(name);
    await d.getByLabel('Phone', { exact: true }).fill(phone);
    await d.getByLabel('Date of birth').fill(dob);
    await d.getByLabel('Gender').selectOption(gender);
    await d.getByLabel('Doctor').selectOption({ label: doctorName });
    await d.getByRole('button', { name: 'Check in' }).click();
  }
  /** Token number shown on the "Checked in" slip. */
  async token() {
    const d = this.page.getByRole('dialog', { name: 'Checked in' });
    await d.waitFor();
    return (await d.getByText(/^#\d+$/).first().innerText()).trim();
  }
}

export class ConsultPage {
  constructor(page) { this.page = page; }
  async open(entryId, step = 'reason') {
    await this.page.goto(`${APP}/consult?entry=${encodeURIComponent(entryId)}&step=${step}`);
    await this.page.getByLabel('Clinical safety').waitFor();
  }
  step(name) { return this.page.getByRole('navigation', { name: 'Consultation steps' }).getByRole('button', { name: new RegExp(`\\d\\. ${name}$`) }).click(); }
  async diagnose(text) { await this.step('Assess'); await this.page.getByLabel('Diagnosis', { exact: true }).fill(text); }
  async openClose() { await this.page.getByRole('button', { name: 'Close consultation' }).click(); }
  signButton() { return this.page.getByRole('button', { name: 'Sign note and send to checkout' }); }
  /** Resolves once the visit shows as handed to checkout. */
  sentToCheckout() { return this.page.getByRole('status').filter({ hasText: 'Sent to checkout.' }).first().waitFor(); }
  async prescribe(drug) {
    await this.step('Plan');
    await this.page.locator('#rx-drug-0').fill(drug);
  }
  rxSave() { return this.page.getByRole('button', { name: 'Save', exact: true }).click(); }
  allergyWarning() { return this.page.getByRole('alert').filter({ hasText: /Allergy warning/ }).first(); }
}

export class CheckoutPage {
  constructor(page) { this.page = page; }
  async open(queueEntryId, patientName) { await this.page.goto(`${APP}/checkout/${encodeURIComponent(queueEntryId)}`); await this.page.getByText(patientName).first().waitFor(); }
  addCharge(name) { return this.page.getByRole('button', { name: new RegExp(`${name}.*\\d`) }).first().click(); } // the catalogue tile shows the price
  /** Tenders the whole balance in the first payment line ("Rest") and collects. */
  async collect() {
    const rest = this.page.getByRole('button', { name: 'Rest' }).first();
    if (await rest.isEnabled()) await rest.click(); // disabled when line 1 already holds the whole balance
    await this.page.getByRole('button', { name: /^Collect/ }).click();
  }
  /** Resolves when the invoice reads as fully paid. */
  async paid(invoiceRe = /INV-/) {
    await this.page.getByText(invoiceRe).first().waitFor();
    await this.page.getByLabel('Amount 1').waitFor({ state: 'detached' }); // the tender form is gone once nothing is due
  }
}
