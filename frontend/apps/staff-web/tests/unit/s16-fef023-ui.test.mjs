// FEF-023: packages/ui markup and pure logic. The package is TSX source; it is transpiled with the repo's TypeScript into a
// scratch folder inside packages/ui (so react resolves) and rendered with react-dom/server. Interaction (sheet, keyboard,
// virtualisation) is covered in the browser by tests/e2e/s16-fef-023-024-ui.mjs.
import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const uiDir = fileURLToPath(new URL("../../../../packages/ui/", import.meta.url));
const out = join(uiDir, ".test-out");
const req = createRequire(join(uiDir, "package.json"));
const ts = createRequire(import.meta.url)("typescript");
const { renderToStaticMarkup } = createRequire(import.meta.url)("react-dom/server");
const React = req("react");
let ui;

before(async () => {
  rmSync(out, { recursive: true, force: true });
  mkdirSync(out, { recursive: true });
  for (const f of readdirSync(join(uiDir, "src")).filter((n) => /\.tsx?$/.test(n))) {
    const src = readFileSync(join(uiDir, "src", f), "utf8").replace(/from "(\.\/[^"]+)\.tsx?"/g, 'from "$1.mjs"');
    const js = ts.transpileModule(src, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText;
    writeFileSync(join(out, f.replace(/\.tsx?$/, ".mjs")), js);
  }
  // print/ is not under test; stub the re-export
  writeFileSync(join(out, "index.mjs"), ["Icon", "controls", "feedback", "Table", "Sheet"].map((m) => `export * from "./${m}.mjs";`).join("\n"));
  ui = await import(pathToFileURL(join(out, "index.mjs")).href);
});
after(() => rmSync(out, { recursive: true, force: true }));
const html = (el) => renderToStaticMarkup(el);
const h = React.createElement;

test("AC5: StatusPill always renders an icon (svg) and text, for every tone", () => {
  for (const tone of ["ok", "warn", "danger", "info", "sync", "urgent", "neutral"]) {
    const m = html(h(ui.StatusPill, { tone, label: "Waiting" }));
    assert.match(m, /<svg/); assert.match(m, /Waiting/);
  }
});

test("Field wires label, hint, error, aria-describedby and aria-invalid", () => {
  const m = html(h(ui.Field, { label: "Phone", hint: "With country code", error: "Invalid", required: true }, (p) => h(ui.Input, p)));
  assert.match(m, /<label[^>]*for="([^"]+)"/);
  const id = /for="([^"]+)"/.exec(m)[1];
  assert.ok(m.includes(`id="${id}"`));
  assert.match(m, /aria-invalid="true"/);
  assert.match(m, new RegExp(`aria-describedby="${id}-hint ${id}-err"`.replace(/[:]/g, ".")));
  assert.match(m, /role="alert"/);
  const plain = html(h(ui.Field, { label: "Notes" }, (p) => h(ui.Textarea, p)));
  assert.ok(!plain.includes("aria-invalid") && !plain.includes("aria-describedby"));
});

test("IconButton is named, Switch is a role=switch checkbox", () => {
  assert.match(html(h(ui.IconButton, { icon: "plus", label: "Add item" })), /aria-label="Add item"/);
  assert.match(html(h(ui.Switch, { label: "Alerts" })), /role="switch"/);
});

test("Table: sortable headers, row cells carry data-label (card mode), activatable rows are tabbable", () => {
  const cols = [{ key: "n", header: "Name", render: (r) => r.n, sortValue: (r) => r.n }, { key: "s", header: "Status", render: (r) => r.s }];
  const rows = [{ id: "1", n: "B", s: "Done" }, { id: "2", n: "A", s: "Late" }];
  const m = html(h(ui.Table, { caption: "T", columns: cols, rows, rowKey: (r) => r.id, onRowActivate: () => {} }));
  assert.match(m, /role="table" aria-label="T"/);
  assert.match(m, /aria-sort="none"/);
  assert.equal((m.match(/data-label="Name"/g) ?? []).length, 2);
  assert.equal((m.match(/data-label="Status"/g) ?? []).length, 2);
  assert.equal((m.match(/tabindex="0"/g) ?? []).length, 1);   // roving: one tab stop
  assert.equal((m.match(/tabindex="-1"/g) ?? []).length, 1);
  assert.ok(!html(h(ui.Table, { caption: "T", columns: cols, rows, rowKey: (r) => r.id })).includes("tabindex"));
  assert.match(html(h(ui.Table, { caption: "T", columns: cols, rows: [], rowKey: (r) => r.id, empty: h("p", null, "None") })), /None/);
});

test("AC4(table): above 300 rows only a window is mounted; at 300 everything is", () => {
  const cols = [{ key: "n", header: "N", render: (r) => r.n }];
  const mk = (n) => Array.from({ length: n }, (_, i) => ({ id: String(i), n: `row${i}` }));
  const big = html(h(ui.Table, { caption: "B", columns: cols, rows: mk(1000), rowKey: (r) => r.id }));
  const mounted = (big.match(/role="row" aria-rowindex/g) ?? []).length;
  assert.ok(mounted > 0 && mounted < 40, `mounted ${mounted}`);
  assert.match(big, /aria-rowcount="1001"/);
  assert.equal((html(h(ui.Table, { caption: "B", columns: cols, rows: mk(300), rowKey: (r) => r.id })).match(/role="row" aria-rowindex/g) ?? []).length, 300);
});

test("windowRange and nextRow (keyboard navigation) boundaries", () => {
  assert.deepEqual(ui.windowRange(0, 480, 48, 1000, 6), [0, 16]);
  assert.deepEqual(ui.windowRange(48 * 990, 480, 48, 1000, 6), [984, 1000]);
  assert.equal(ui.nextRow("ArrowDown", 9, 10), 9);
  assert.equal(ui.nextRow("ArrowUp", 0, 10), 0);
  assert.equal(ui.nextRow("End", 0, 1000), 999);
  assert.equal(ui.nextRow("Home", 500, 1000), 0);
  assert.equal(ui.nextRow("PageDown", 995, 1000), 999);
  assert.equal(ui.nextRow("a", 1, 10), -1);
  assert.equal(ui.nextRow("ArrowDown", 0, 0), -1);
});

test("States: Skeleton is a busy status, ErrorState an alert with retry, EmptyState has a title", () => {
  assert.match(html(h(ui.Skeleton, {})), /role="status" aria-busy="true"/);
  const e = html(h(ui.ErrorState, { message: "Failed", onRetry: () => {} }));
  assert.match(e, /role="alert"/); assert.match(e, /Retry/);
  assert.match(html(h(ui.EmptyState, { title: "Nothing" })), /Nothing/);
});

test("Toast provider renders a polite and an assertive live region", () => {
  const m = html(h(ui.ToastProvider, null, h("span", null, "x")));
  assert.match(m, /aria-live="polite"/); assert.match(m, /aria-live="assertive"/);
});

test("Tabs: roving tabindex, only the selected tab is a tab stop", () => {
  const m = html(h(ui.Tabs, { label: "S", value: "b", onChange() {}, tabs: [{ id: "a", label: "A" }, { id: "b", label: "B" }] }));
  assert.match(m, /role="tablist"/);
  assert.equal((m.match(/tabindex="0"/g) ?? []).length, 1);
  assert.match(m, /aria-selected="true"[^>]*tabindex="0"|tabindex="0"[^>]*aria-selected="true"/);
});
