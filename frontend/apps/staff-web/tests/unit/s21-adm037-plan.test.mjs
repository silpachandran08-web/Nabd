// ADM-037: owner plan page model and wiring (change controls absent for non-owners, proration before confirming, downgrade warnings
// and typed name, step-up bound to the target, loading/empty/error states, usage meters, invoices). Synthetic data only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { defaultEffective, meterPct, nameMatches, stepUpTarget } from "../../app/components/plan/planAccount.ts";

const src = readFileSync(new URL("../../app/components/plan/PlanAccountPanel.tsx", import.meta.url), "utf8");
const en = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/en/billing.json", import.meta.url), "utf8")).plan;

test("model: step-up target matches the server's, name check is trimmed and case-insensitive, meters cap at 100, timing defaults", () => {
  assert.equal(stepUpTarget("clinic-plus", "now"), "plan-change:clinic-plus:now"); // OwnerPlanService.stepUpTarget
  assert.ok(nameMatches("  sunrise CLINIC ", "Sunrise Clinic"));
  assert.ok(!nameMatches("Sunrise", "Sunrise Clinic"));
  assert.equal(meterPct(3, 10), 30);
  assert.equal(meterPct(12, 10), 100);
  assert.equal(meterPct(3, null), null);
  const plan = (monthlyMinor) => ({ code: "x", name: "X", version: 1, monthlyMinor, pricingBasis: "per_clinic", modules: [], seatLimit: null, branchLimit: null });
  assert.equal(defaultEffective(100000, plan(200000)), "now");
  assert.equal(defaultEffective(100000, plan(50000)), "renewal");
});

test("AC1: the prorated charge comes from the server preview and is shown before the confirm button; the change carries the bound step-up", () => {
  assert.match(src, /\/setup\/subscription\/change-preview\?targetPlanCode=\$\{encodeURIComponent\(target\.code\)\}&effective=\$\{effective\}/);
  assert.ok(src.indexOf('data-testid="proration"') < src.indexOf('t("confirm")'));
  assert.match(src, /requestStepUpToken\(code, stepUpTarget\(target\.code, effective\)\)/);
  assert.match(src, /headers: \{ "X-Step-Up-Token": su\.token \}/);
  assert.match(en.chargeNow, /\{amount\}/);
});

test("AC2: a downgrade lists the read-only modules, says no data is deleted and needs the clinic name typed before confirming", () => {
  assert.match(src, /data-testid="read-only-modules"/);
  assert.match(src, /t\("noDataDeleted"\)/);
  assert.match(en.noDataDeleted, /No data is deleted/);
  assert.match(src, /const ready = !!p && !p\.branchesExceedTarget && \(!down \|\| nameMatches\(typed, clinic\)\) && \/\^\\d\{6\}\$\/\.test\(code\)/);
  assert.match(src, /confirmClinicName: typed/);
  assert.match(src, /t\("branchesExceed"\)/);
});

test("AC3: usage shows seats, branches, WhatsApp utility messages and cost, AI checks; meters not connected say so instead of 0", () => {
  for (const k of ["seats", "branches", "whatsapp", "whatsappCost", "aiChecks"]) assert.match(src, new RegExp(`t\\("${k}"\\)`), k);
  assert.match(src, /u\.whatsappPassThroughMinor == null \? t\("notConnected"\)/);
  assert.match(src, /u\.aiChecks \?\? t\("notMetered"\)/);
});

test("AC4 (built part): Nabd invoices list with loading, empty and error states; a PDF link only when the billing gateway supplies one", () => {
  assert.match(src, /getter<\{ items: Invoice\[\]; nextCursor: string \| null \}>\("\/setup\/subscription\/invoices"\)/);
  for (const k of ["loadingInvoices", "invoicesFailed", "noInvoices", "pdfPending"]) assert.match(src, new RegExp(`t\\("${k}"\\)`), k);
  assert.match(src, /\{i\.pdfUrl \? <a className=\{styles\.link\} href=\{i\.pdfUrl\}>/);
});

test("restricted controls are absent: change buttons only for the owner with setup:edit, after mount; summary has loading/error/empty states", () => {
  assert.match(src, /const canChange = mounted && isOwner\(\) && can\("setup:edit"\)/);
  assert.match(src, /: canChange && <button/);
  assert.ok(!/disabled=\{!canChange/.test(src));
  assert.match(src, /if \(sumQ\.isPending\) return <Skeleton/);
  assert.match(src, /if \(sumQ\.isError\) return <ErrorState/);
  assert.match(src, /if \(!s\.planCode \|\| !s\.currency\) return <EmptyState/);
  assert.match(readFileSync(new URL("../../app/(clinic)/plan/page.tsx", import.meta.url), "utf8"), /<PlanAccountPanel \/>/);
});
