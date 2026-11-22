// PLT-022: owner.setup Branches tab. Model (step-up target, code rule, plan limit, polling) and wiring (owner-only controls absent
// for everyone else, wizard without a region field, 402 upgrade link, live progress, failed step with retry, states, en + hi).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { SECTIONS, atLimit, codeOk, draftOk, moving, progress, stepUpTarget } from "../../app/components/branches/branches.ts";
import { TABS, isTabRoute, tabHref } from "../../app/(clinic)/setup/setupTabs.ts";

const read = (p) => readFileSync(new URL(p, import.meta.url), "utf8");
const src = read("../../app/components/branches/BranchesPanel.tsx");
const layout = read("../../app/(clinic)/setup/layout.tsx");
const msgs = (l) => JSON.parse(read(`../../../../packages/i18n/messages/${l}/billing.json`)).branches;
const keys = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (typeof v === "object" ? keys(v, `${p}${k}.`) : [`${p}${k}`]));
const job = (status, steps) => ({ provisioningJobId: "j", branchTenantId: "t", name: "B", branchCode: "B2", status, failedStep: null, steps: steps.map((s, i) => ({ stepName: `s${i}`, status: s })) });

test("model: step-up target and code rule match the server; plan limit; polling only while a job moves; progress", () => {
  assert.equal(stepUpTarget("BLR-2"), "branch-add:blr-2"); // OwnerBranchService.stepUpTarget
  assert.deepEqual([...SECTIONS], ["departments", "charges", "policies", "roles", "templates", "hours"]); // ConfigBundle.SECTIONS
  for (const ok of ["B2", "BLR-2", "a1b2c3d4e5f6g7h8i9j0"]) assert.ok(codeOk(ok), ok);
  for (const bad of ["", "B", "-B2", "B 2", "B_2", "a1b2c3d4e5f6g7h8i9j0k"]) assert.ok(!codeOk(bad), bad);
  assert.ok(atLimit({ branchLimit: 1, branchesInUse: 1 }) && atLimit({ branchLimit: 2, branchesInUse: 3 }));
  assert.ok(!atLimit({ branchLimit: 3, branchesInUse: 1 }) && !atLimit({ branchLimit: null, branchesInUse: 9 }));
  assert.ok(moving([job("running", [])]) && moving([job("failed", []), job("queued", [])]));
  assert.ok(!moving([]) && !moving([job("failed", [])]));
  assert.deepEqual(progress(job("running", ["done", "done", "running", "queued", "queued"])), { done: 2, total: 5 });
  const d = { name: "Branch", branchCode: "B2", address: "1 Road", timezone: "Asia/Kolkata", copyFromTenantId: "x", copy: [] };
  assert.ok(draftOk(d), "copying nothing is allowed");
  for (const patch of [{ name: " " }, { branchCode: "B" }, { address: "" }, { timezone: "" }, { copyFromTenantId: "" }]) assert.ok(!draftOk({ ...d, ...patch }), JSON.stringify(patch));
});

test("AC1: the wizard collects name, code, address, timezone, copy-from and the copy checklist, and posts with the bound step-up", () => {
  for (const k of ["name", "code", "address", "timezone", "copyFrom", "copyWhat"]) assert.match(src, new RegExp(`t\\("${k}"\\)`), k);
  assert.match(src, /SECTIONS\.map\(\(s\) => \(\s*<label key=\{s\}[^>]*><input type="checkbox"/);
  assert.match(src, /requestStepUpToken\(code, stepUpTarget\(d\.branchCode\)\)/);
  assert.match(src, /api\.post<[^>]+>\("\/owner\/branches",\s*\{ \.\.\.d, name: d\.name\.trim\(\), address: d\.address\.trim\(\) \}, \{ headers: \{ "X-Step-Up-Token": su\.token \} \}\)/);
});

test("AC2: the checklist says what is never copied", () => {
  assert.match(src, /t\("neverCopied"\)/);
  assert.match(msgs("en").neverCopied, /Patients, staff accounts.*never copied/);
});

test("AC3: no region field anywhere in the wizard; the pinned region is shown as text", () => {
  const draft = read("../../app/components/branches/branches.ts").match(/export type Draft = \{[^}]*\}/)[0];
  assert.doesNotMatch(draft, /region/i, "the request never carries a region");
  assert.doesNotMatch(src, /<(select|input)[^>]*region/i);
  assert.match(src, /data-testid="region-pinned">\{t\("regionPinned"/);
  assert.match(msgs("en").regionPinned, /cannot be changed/);
});

test("AC4: at the plan limit the Add button is replaced by the upgrade link; a 402 from the server shows the server's upgrade link", () => {
  assert.match(src, /canAdd && \(atLimit\(list\)\s*\? <div[^>]*data-testid="plan-limit">\{t\("planLimit"[^}]*\}\)\} <Link className=\{styles\.link\} href="\/plan">/);
  assert.match(src, /e instanceof ApiError && e\.status === 402\) setUpgradeUrl\(typeof e\.problem\.upgradeUrl === "string" \? e\.problem\.upgradeUrl : "\/plan"\)/);
  assert.match(src, /\{upgradeUrl && <Link className=\{styles\.link\} href=\{upgradeUrl\}>/);
});

test("AC5: live progress by polling only while a job moves; the failed step is named and retried", () => {
  assert.match(src, /refetchInterval: \(query\) => \(moving\(query\.state\.data\?\.jobs \?\? \[\]\) \? 2000 : false\)/);
  assert.match(src, /role="progressbar"/);
  assert.match(src, /job\.failedStep \? t\("stoppedAt", \{ step: stepName\(job\.failedStep\) \}\)/);
  assert.match(src, /api\.post\(`\/owner\/branches\/jobs\/\$\{job\.provisioningJobId\}\/retry`, \{\}\)/);
  for (const s of ["create_tenant", "seed_masters", "copy_config", "provision_whatsapp", "go_live"]) assert.ok(msgs("en").step[s], s);
});

test("restricted controls are absent: Add and Retry only for the owner with setup:edit, after mount; the tab only for the owner", () => {
  assert.match(src, /const canAdd = mounted && isOwner\(\) && can\("setup:edit"\)/);
  assert.match(src, /\{job\.failedStep && canAdd && <button/);
  assert.match(src, /\{canAdd && \(atLimit\(list\)/);
  assert.ok(!/disabled=\{!canAdd/.test(src));
  assert.match(layout, /\(t\.key !== "branches" \|\| owner\)/);
  assert.match(layout, /setOwner\(isOwner\(\)\)/);
  assert.ok(TABS.some((t) => t.key === "branches") && isTabRoute("branches"));
  assert.equal(tabHref("branches"), "/setup/branches");
  assert.match(read("../../app/(clinic)/setup/branches/page.tsx"), /<BranchesPanel \/>/);
});

test("loading, error and empty states; a refetch never swaps the page for a skeleton", () => {
  assert.match(src, /if \(q\.isPending\) return <Skeleton/);
  assert.match(src, /if \(q\.isError\) return <ErrorState/);
  assert.match(src, /list\.items\.length === 0 \? <EmptyState/);
  assert.doesNotMatch(src, /isFetching|isRefetching/);
});

test("i18n: every string comes from billing.branches, with the same keys in en and hi", () => {
  assert.match(src, /useTranslations\("billing\.branches"\)/);
  assert.deepEqual(keys(msgs("hi")), keys(msgs("en")));
  const used = [...src.matchAll(/\bt\("([a-zA-Z.]+)"/g)].map((m) => m[1]);
  assert.ok(used.length > 30);
  for (const k of used) assert.ok(keys(msgs("en")).includes(k), `missing en string ${k}`);
});
