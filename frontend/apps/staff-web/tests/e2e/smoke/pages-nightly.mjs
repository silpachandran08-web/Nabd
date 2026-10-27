// FEF-050 page objects for the screens the nightly journeys J2-J4, J7, J8, J10-J15 drive. Same rule as pages.mjs:
// role/label locators, the ones staff see. Strings of migrated screens (login) come from the catalogue.
import { APP, msg } from './harness.mjs';

const settle = (page) => page.waitForLoadState('networkidle').catch(() => {});

export class ArrivalsCheckIn {
  constructor(page) { this.page = page; }
  async open() { await this.page.goto(`${APP}/arrivals`); await this.page.getByRole('button', { name: 'Check in', exact: true }).first().waitFor(); }
  /** Quick action -> pick today's booking -> submit. Returns once the dialog flow has been submitted. */
  async checkInBooked() {
    const p = this.page;
    await p.getByRole('button', { name: 'Check in', exact: true }).first().click();
    const dlg = p.getByRole('dialog');
    await dlg.waitFor();
    await dlg.getByRole('button', { name: 'Check in', exact: true }).click();
  }
  slip() { return this.page.getByRole('dialog', { name: 'Checked in' }); }
  async closeSlip() { await this.slip().getByRole('button', { name: 'Done' }).click(); await this.slip().waitFor({ state: 'detached' }); }
  alerts() { return this.page.getByRole('alert'); }
}

export class SchedulePage {
  constructor(page) { this.page = page; }
  async open() { await this.page.goto(`${APP}/schedule`); await this.page.getByRole('heading', { name: /Schedule/ }).waitFor(); await settle(this.page); }
  available() { return this.page.getByRole('button', { name: '+ Available' }); }
  cell(label) { return this.page.getByRole('button', { name: label, exact: true }); }
  async pickPatient(query, name) {
    const d = this.page.getByRole('dialog');
    await d.locator('#appt-patient').fill(query);
    await d.getByRole('button', { name: new RegExp(name) }).click();
    return d;
  }
  waitlistButton() { return this.page.getByRole('button', { name: /Waitlist/ }).first(); }
  newAppointment() { return this.page.getByRole('button', { name: '+ New appointment' }); }
}

export class NursingPage {
  constructor(page) { this.page = page; }
  async open() { await this.page.goto(`${APP}/nursing`); await this.page.getByRole('heading', { name: 'Vitals Worklist' }).waitFor(); }
  captureRow() { return this.page.locator('tbody').getByRole('button', { name: /Capture vitals/ }).first(); }
  dialog() { return this.page.getByRole('dialog', { name: /Capture vitals/ }); }
  async fill(v) {
    const d = this.dialog();
    for (const [id, value] of Object.entries(v)) await d.locator(`#${id}`).fill(String(value));
  }
  save() { return this.dialog().getByRole('button', { name: 'Save and mark ready' }); }
  viewButton() { return this.page.locator('tbody').getByRole('button', { name: /View vitals/ }).first(); }
}

export class OtcPage {
  constructor(page) { this.page = page; }
  async open() { await this.page.goto(`${APP}/checkout/otc`); await this.page.getByRole('button', { name: /Plain Item/ }).waitFor(); }
  item(name) { return this.page.getByRole('button', { name: new RegExp(name) }); }
  checkout() { return this.page.getByRole('button', { name: 'Checkout', exact: true }); }
}

export class PackagesDesk {
  constructor(page) { this.page = page; }
  async open() { await this.page.goto(`${APP}/packages`); await this.page.getByRole('heading', { name: /Packages/ }).first().waitFor(); }
  tab(name) { return this.page.getByRole('button', { name }); }
  async redeemFirst() { await this.tab('Sessions & redemptions').click(); await this.page.getByRole('button', { name: 'Redeem', exact: true }).first().click(); }
  async requestRefund(reason) {
    const p = this.page;
    await p.getByRole('button', { name: 'Ledger' }).first().click();
    await p.getByRole('button', { name: 'Calculate refund' }).click();
    await p.getByPlaceholder(/Patient relocated/).fill(reason);
    await p.getByRole('button', { name: 'Request refund' }).click();
  }
  async approveFirst() {
    const p = this.page;
    await this.tab('Refunds & adjustments').click();
    await p.getByRole('button', { name: 'Review' }).click();
    await p.getByRole('region', { name: 'Review refund' }).waitFor();
    await p.getByRole('button', { name: /^Approve/ }).click();
  }
}

export class DayClosePage {
  constructor(page) { this.page = page; }
  async open() { await this.page.goto(`${APP}/billing`); await this.page.getByRole('heading', { name: /Billing & Day Close/ }).waitFor({ timeout: 15000 }); }
  async close(counted) {
    const p = this.page;
    await p.getByLabel('Enter counted cash').fill(String(counted));
    await p.getByRole('button', { name: /^(Start day close|Close again)$/ }).click();
    await p.getByRole('dialog').getByRole('button', { name: 'Close the day' }).click();
    await p.getByText('Day closed', { exact: true }).waitFor();
  }
  async unlock(reasonLabel = 'Missing bill found afterwards') {
    const p = this.page;
    await p.getByRole('button', { name: 'Unlock day' }).click();
    const d = p.getByRole('dialog');
    await d.getByLabel(reasonLabel).check();
    await d.getByRole('button', { name: 'Unlock day' }).click();
    await d.waitFor({ state: 'detached' });
  }
  history() { return this.page.locator('section[aria-label="Close history"] li'); }
}

export class InvitePage {
  constructor(page, locale = 'en') { this.page = page; this.L = (k) => msg(locale, k); }
  async accept(token, pin) {
    const p = this.page;
    await p.goto(`${APP}/accept-invite/${token}`);
    await p.locator('#pin').fill(pin); await p.locator('#confirmPin').fill(pin);
    await p.getByRole('button', { name: this.L('login.invite.submit') }).click();
  }
  async enrol(code) {
    const p = this.page;
    await p.getByTestId('mfa-secret').waitFor();
    await p.locator('#setupCode').fill(code);
    await p.getByRole('button', { name: this.L('login.mfa.confirm') }).click();
    await p.getByRole('button', { name: this.L('login.invite.signInNow') }).waitFor();
  }
  signInNow() { return this.page.getByRole('button', { name: this.L('login.invite.signInNow') }).click(); }
}

export class LoginPage {
  constructor(page, locale = 'en') { this.page = page; this.L = (k) => msg(locale, k); }
  async open() { await this.page.goto(`${APP}/login`); await this.page.locator('#tenantSlug').waitFor(); }
  async signIn({ slug, email, pin }) {
    const p = this.page;
    await p.locator('#tenantSlug').fill(slug); await p.locator('#email').fill(email); await p.locator('#pin').fill(pin);
    await p.getByRole('button', { name: this.L('login.signIn.submit'), exact: true }).click();
  }
  async verify(code) {
    const p = this.page;
    await p.locator('#code').fill(code);
    await p.getByRole('button', { name: this.L('login.mfa.verify'), exact: true }).click();
  }
}

export class AccountPage {
  constructor(page) { this.page = page; }
  async open() { await this.page.goto(`${APP}/account`); await this.page.getByRole('heading', { name: 'Account & Security' }).waitFor(); }
  async breakGlass(reason) {
    await this.page.locator('#bgReason').fill(reason);
    await this.page.getByRole('button', { name: 'Activate emergency access' }).click();
    await this.page.getByText(/Emergency access activated/).waitFor();
  }
}

export class ProvisioningConsole {
  constructor(page) { this.page = page; }
  async open() { await this.page.goto(`${APP}/platform/provisioning`); await this.page.getByRole('heading', { name: 'Tenant Provisioning' }).waitFor(); }
  async create(f) {
    const p = this.page;
    await p.getByRole('button', { name: '+ New tenant' }).click();
    await p.fill('#tenantSlug', f.slug); await p.fill('#tenantName', f.name); await p.fill('#brandName', f.brand);
    await p.fill('#ownerName', f.owner); await p.fill('#ownerEmail', f.email); await p.fill('#ownerMobile', f.mobile);
    await p.getByRole('button', { name: 'Provision tenant' }).click();
    await p.getByText(f.name).first().waitFor();
  }
  run() { return this.page.getByRole('button', { name: 'Run provisioning' }); }
}
