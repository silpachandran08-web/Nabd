// FEF-025: light theme parity with prototype nb.css, pre-paint theme script, sign-in cookie mirroring, switch wiring,
// print stays paper colours, literal-colour gate. Synthetic data only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { THEME_BOOT_SCRIPT, resolveTheme, themePrefFromCookies, themeCookie, themePrefOf } from "../../app/shell/theme.ts";
import { proxy, signInCookiesFrom } from "../../app/lib/bff.ts";
import { printCss, PAPERS } from "../../../../packages/ui/src/print/paper.ts";

const read = (rel) => readFileSync(new URL(rel, import.meta.url), "utf8");
/** [hue deg, saturation, lightness] of #rrggbb. */
function hsl(hex) {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2, d = max - min;
  if (!d) return [0, 0, l];
  const h = max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [(h * 60 + 360) % 360, d / (1 - Math.abs(2 * l - 1)), l];
}
const tok = (f) => JSON.parse(read(`../../../../packages/tokens/src/source/${f}`));
const { scan } = await import("../../scripts/check-tokens.mjs");

// --nb-* token -> prototype nb.css variable (DESIGN.md §1.7). Tokens with no prototype counterpart are listed explicitly.
const MAP = {
  "bg-root": "bg-root", "bg-canvas": "bg-canvas", "bg-nav": "bg-nav", "surface-1": "s1", "surface-2": "s2", "surface-3": "s3",
  "surface-overlay": "overlay", scrim: "scrim", "border-subtle": "b-sub", "border-default": "b-def", "border-strong": "b-str",
  "accent-500": "acc", "accent-fill": "acc-fill", "accent-gradient": "acc-grad", "accent-tint": "acc-tint", "accent-glow": "acc-glow", "on-accent": "on-acc",
  "danger-500": "crit", "danger-tint": "crit-t", "ok-500": "ok", "ok-tint": "ok-t", "success-500": "ok", "success-tint": "ok-t",
  "warn-500": "warn", "warn-tint": "warn-t", "info-500": "info", "info-tint": "info-t", "sync-500": "sync", "sync-tint": "sync-t",
  "urgent-500": "urg", "urgent-tint": "urg-t", "text-primary": "tx", "text-secondary": "tx2", "text-tertiary": "tx3", "text-disabled": "tx4",
  "focus-ring": "acc-300", "shadow-md": "shadow-md",
};
const NO_PROTOTYPE = ["danger-600", "info-600"];
const norm = (v) => String(v).toLowerCase().replace(/\s+/g, "").replace(/(^|[^\d])\.(\d)/g, "$10.$2").replace(/(\.\d*?)0+(?=[),])/g, "$1").replace(/\.(?=[),])/g, "");
function block(css, selector) {
  const i = css.indexOf(`${selector} {`);
  const body = css.slice(i, css.indexOf("\n}", i));
  return Object.fromEntries([...body.matchAll(/--([\w-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]));
}

test("AC1: every dark token has a light counterpart equal to the prototype nb.css light value (deviations listed with a reason)", () => {
  const proto = read("../../../../packages/tokens/src/source/prototype-nb.tokens.css");
  const pDark = block(proto, ":root"), pLight = { ...pDark, ...block(proto, ':root[data-nb-theme="light"]') };
  const dark = tok("color.dark.json"), light = tok("color.light.json");
  const keys = Object.keys(dark).filter((k) => !k.startsWith("_"));
  const dev = light._deviations ?? {};
  for (const k of keys) {
    assert.ok(k in light, `light lacks ${k}`);
    if (NO_PROTOTYPE.includes(k)) continue;
    assert.ok(k in MAP, `${k}: add it to MAP or NO_PROTOTYPE`);
    assert.ok(MAP[k] in pLight, `prototype has no --${MAP[k]}`);
    if (k in dev) { assert.notEqual(norm(light[k]), norm(pLight[MAP[k]]), `${k} is listed as a deviation but equals the prototype`); continue; }
    assert.equal(norm(light[k]), norm(pLight[MAP[k]]), `${k} (light) vs prototype --${MAP[k]}`);
  }
  for (const [k, why] of Object.entries(dev)) assert.ok(keys.includes(k) && /AA|:1/.test(why), `deviation ${k} needs a contrast reason`);
  // The spec's named values hold exactly: accent text darkens, fills stay bright cyan with dark ink, clinical states darken.
  const want = { "accent-fill": "#2ed3e0", "on-accent": "#04231f", "urgent-500": "#b8530f", "warn-500": "#946200", "ok-500": "#0b7a52", "info-500": "#1c68aa" };
  for (const [k, v] of Object.entries(want)) assert.equal(light[k], v, k);
  // T1-BUG-5: accent and crit text keep the spec's hue, darkened (a listed deviation) only as far as AA on the light page surfaces needs.
  for (const [k, spec] of [["accent-500", "#0b7f8b"], ["danger-500", "#d42a2d"]]) {
    assert.ok(k in dev, `${k} is a listed deviation`);
    assert.ok(Math.abs(hsl(light[k])[0] - hsl(spec)[0]) < 1.5, `${k} keeps the hue of ${spec}`);
    assert.ok(hsl(light[k])[2] < hsl(spec)[2], `${k} is darker than ${spec}`);
  }
  const css = read("../../../../packages/tokens/dist/tokens.css");
  assert.match(css, /:root\[data-theme="light"\] \{\n {2}color-scheme: light;/);
  assert.match(css, /--nb-accent-fill: #2ed3e0;/);
});

test("AC4/AC5: contrast gate covers both themes; crit text on white >= 4.5 in light; dark ink on the accent fill", () => {
  const rep = JSON.parse(read("../../../../packages/tokens/dist/contrast-report.json"));
  assert.equal(rep.pass, true);
  const pair = (theme, fg, bg) => rep.pairs.find((p) => p.theme === theme && p.fg === fg && p.bg === bg);
  for (const theme of ["dark", "light"]) {
    for (const [fg, bg] of [["danger-500", "surface-1"], ["danger-500", "danger-tint"], ["on-accent", "accent-fill"], ["text-tertiary", "surface-3"], ["urgent-500", "surface-1"], ["warn-500", "surface-1"]]) {
      const p = pair(theme, fg, bg);
      assert.ok(p && p.ratio >= 4.5, `${theme} ${fg}/${bg} ${p?.ratio}`);
    }
  }
  assert.equal(tok("color.light.json")["surface-1"], "#ffffff", "light crit pair is measured on white");
  // T1-BUG-5: accent and crit text where the shell puts them (page, nav, inset surfaces, the active-nav tint), both themes.
  const placed = [["accent-500", "bg-root"], ["accent-500", "bg-nav"], ["accent-500", "surface-2"], ["accent-500", "surface-3"], ["accent-500", "accent-tint", "bg-root"],
    ["accent-500", "accent-tint", "bg-nav"], ["danger-500", "bg-root"], ["danger-500", "surface-2"], ["danger-500", "surface-3"], ["danger-500", "danger-tint", "bg-root"]];
  for (const theme of ["dark", "light"]) {
    for (const [fg, bg, base] of placed) {
      const p = rep.pairs.find((x) => x.theme === theme && x.fg === fg && x.bg === bg && x.base === base);
      assert.ok(p && p.ratio >= 4.5, `${theme} ${fg}/${bg}/${base} ${p?.ratio}`);
    }
  }
});

test("AC5: the allergy banner keeps its icon and shape in both themes (no theme-specific override)", () => {
  const strip = read("../../app/components/consult/SafetyStrip.tsx");
  assert.match(strip, /safetyAllergy[\s\S]*<Icon name="alert"/);
  for (const f of ["../../app/components/consult/consultParts.module.css", "../../app/components/consult/SafetyStrip.tsx"]) assert.ok(!read(f).includes("data-theme"), f);
});

function boot(cookie, osLight) {
  const documentElement = { dataset: {} };
  vm.runInNewContext(THEME_BOOT_SCRIPT, { document: { cookie, documentElement }, matchMedia: (q) => ({ matches: q === "(prefers-color-scheme: light)" && osLight }) });
  return documentElement.dataset.theme;
}

test("AC2/AC3: the pre-paint script resolves the theme from the cookie, System from the OS, same as resolveTheme", () => {
  const cases = [["", true, "light"], ["", false, "dark"], ["nabd_theme=system", true, "light"], ["a=1; nabd_theme=system", false, "dark"],
    ["nabd_theme=dark", true, "dark"], ["x=1; nabd_theme=light; y=2", false, "light"], ["nabd_theme=bogus", true, "light"], ["my_nabd_theme=light", false, "dark"]];
  for (const [cookie, os, want] of cases) {
    assert.equal(boot(cookie, os), want, `${cookie} os-light=${os}`);
    assert.equal(resolveTheme(themePrefFromCookies(cookie), os), want, `resolveTheme ${cookie}`);
  }
  assert.equal(themePrefOf(undefined), "system");
  assert.equal(themeCookie("light"), "nabd_theme=light; Path=/; Max-Age=31536000; Secure; SameSite=Lax");
});

test("AC2: the root layout runs the nonce'd script in <head> and never makes data-theme a React prop", () => {
  const layout = read("../../app/layout.tsx");
  assert.match(layout, /<head>\s*<script nonce=\{nonce\} suppressHydrationWarning dangerouslySetInnerHTML=\{\{ __html: THEME_BOOT_SCRIPT \}\} \/>\s*<\/head>/);
  assert.match(layout, /headers\(\)\)\.get\("x-nonce"\)/);
  assert.ok(!/data-theme=/.test(layout));
  assert.match(layout, /<ThemeBoot \/>/);
});

test("switch: System/Dark/Light in the user menu and on the account page, saved through /me/preferences; old localStorage toggle gone", () => {
  const sw = read("../../app/shell/ThemeSwitch.tsx");
  assert.match(sw, /role="menuitemradio" aria-checked=\{p === pref\}/);
  assert.match(sw, /type="radio"/);
  assert.match(sw, /api\.put\("\/me\/preferences", \{ uiLocale: prefs\.uiLocale \?\? null, uiTheme: next, digitStyle: prefs\.digitStyle \?\? null \}\)/);
  assert.ok(sw.indexOf("keepOnThisMachine(next)") < sw.indexOf("await api"), "applied before the network round trip (< 50 ms, no reload)");
  assert.ok(!/localStorage/.test(sw));
  assert.match(read("../../app/shell/ShellUserMenu.tsx"), /<ThemeSwitch variant="menu" \/>/);
  assert.match(read("../../app/(clinic)/account/page.tsx"), /<ThemeSwitch variant="page" \/>/);
  assert.ok(!/ThemeToggle/.test(read("../../app/TopBar.tsx")));
  for (const l of ["en", "hi"]) {
    const m = JSON.parse(read(`../../../../packages/i18n/messages/${l}/shell.json`));
    for (const k of ["theme", "themeSystem", "themeDark", "themeLight", "themeSaveFailed"]) assert.ok(m[k], `${l}.${k}`);
  }
});

// ---- BFF: sign-in on another machine brings the saved theme with the session cookies ----
const ORIGIN = "https://staff.example.in";
const b64u = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
const at = () => `${b64u({ alg: "RS256" })}.${b64u({ sub: "s1", tenantId: "t1", permissions: [], exp: Math.floor(Date.now() / 1000) + 900 })}.${crypto.randomUUID()}`;
function login(prefs) {
  const calls = [];
  const access = at();
  const fetch = async (url, init = {}) => {
    const path = new URL(url).pathname.replace(/^\/v1/, "");
    calls.push({ path, auth: new Headers(init.headers).get("authorization") });
    if (path === "/auth/login") return Response.json({ accessToken: access, refreshToken: "rt-x", expiresIn: 900 });
    if (path === "/me/preferences") return prefs();
    return new Response(null, { status: 404 });
  };
  const req = new Request(`${ORIGIN}/api/auth/login`, { method: "POST", headers: { origin: ORIGIN, "content-type": "application/json" }, body: "{}" });
  return { calls, access, res: proxy(req, "/auth/login", { core: "https://core.internal/v1", fetch, origins: [ORIGIN], log: () => {} }, true) };
}
const setCookies = (res) => res.headers.getSetCookie();

test("AC3: a sign-in sets nabd_theme from the saved preference with the new token", async () => {
  const l = login(() => Response.json({ uiLocale: "hi", uiTheme: "light", digitStyle: null, clinicDefaultLocale: "en" }));
  const res = await l.res;
  assert.equal(res.status, 200);
  assert.ok(setCookies(res).includes("nabd_theme=light; Path=/; Max-Age=31536000; Secure; SameSite=Lax"));
  assert.ok(setCookies(res).some((c) => c.startsWith("nabd_locale=hi;")));
  assert.ok(setCookies(res).some((c) => c.startsWith("__Host-nabd_at=")), "session cookies still set");
  assert.deepEqual(l.calls.map((c) => c.path), ["/auth/login", "/me/preferences"]);
  assert.equal(l.calls[1].auth, `Bearer ${l.access}`);
});

test("AC3: preferences unavailable -> sign-in still succeeds and no preference cookie is cleared", async () => {
  for (const prefs of [() => new Response(null, { status: 503 }), () => { throw new Error("down"); }, () => Response.json({ ok: true })]) {
    const res = await login(prefs).res;
    assert.equal(res.status, 200);
    assert.ok(!setCookies(res).some((c) => /^nabd_(theme|locale|digits|clinic_locale)=/.test(c)));
    assert.ok(setCookies(res).some((c) => c.startsWith("__Host-nabd_rt=")));
  }
});

test("T1-BUG-4: bearer-mode sign-in reads /me/preferences and sets the same theme and language cookies as the BFF", () => {
  const c = signInCookiesFrom({ uiLocale: "hi", uiTheme: "light", digitStyle: null, clinicDefaultLocale: "en" });
  assert.ok(c.includes("nabd_theme=light; Path=/; Max-Age=31536000; Secure; SameSite=Lax"));
  assert.ok(c.some((x) => x.startsWith("nabd_locale=hi;")));
  for (const none of [null, { ok: true }]) assert.deepEqual(signInCookiesFrom(none), [], "nothing saved or no answer: this machine's cookies stay");
  const page = read("../../app/(auth)/login/page.tsx");
  const body = page.slice(page.indexOf("async function handleTokens"), page.indexOf("async function signedIn"));
  assert.match(body, /if \(!COOKIE_AUTH\)[\s\S]*api\.get<.+?>\("\/me\/preferences", \{ keepSession: true/);
  assert.match(body, /signInCookiesFrom\(prefs\)\) document\.cookie = c/);
  assert.match(body, /window\.location\.replace\(to\)/, "a changed language gets a server render");
});

test("AC6: Rx, invoice and token print are black on white whatever the theme (no theme token inside the print rules)", () => {
  for (const p of PAPERS) {
    const css = printCss(p);
    const print = css.slice(css.indexOf("@media print"));
    assert.ok(!css.includes("var(--nb-"), p);
    assert.match(print, /html,body\{background:#fff!important;color:#000!important/);
    assert.match(print, /\.nbp-sheet,\.nbp-sheet \*\{background:transparent!important;color:#000!important/);
  }
  for (const f of ["../../app/components/consult/RxPrint.tsx", "../../app/components/checkout/InvoiceSheet.tsx", "../../app/components/arrivals/CheckInDialog.tsx"]) assert.match(read(f), /PrintSheet/, f);
});

test("FEF-025.5: literal colours that ignore the theme are flagged; palettes in custom properties and print rules are not", () => {
  assert.deepEqual(scan().filter((x) => x.includes("literal colour")), []);
  assert.equal(scan([{ path: "app/a.module.css", text: ".a{color:#fff;box-shadow:0 1px 2px rgba(0,0,0,.5)}" }]).length, 2);
  assert.equal(scan([{ path: "app/a.module.css", text: ".a{--series-1:#b32284;color:var(--series-1)} @media print { .b { color: #000; } }" }]).length, 0);
});
