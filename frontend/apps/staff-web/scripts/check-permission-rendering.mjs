#!/usr/bin/env node
// NB-054: "permission-restricted elements are absent, never disabled or greyed" — a static check
// for the one anti-pattern that AC forbids, since there's no Playwright/e2e harness in this repo
// yet to assert it at the DOM level. Flags any `disabled={...}` JSX attribute whose element also
// carries a `title`/`aria-label` mentioning permission wording — the "greyed out with a tooltip
// explaining why" pattern the AC explicitly disallows. Real DOM assertion is the upgrade path once
// this app has an e2e suite at all (not just for this ticket).
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const ROOT = join(import.meta.dirname, "..", "app");
const PERMISSION_WORDS = /permission|not allowed|don'?t have access|no access/i;
const violations = [];

function walk(dir) {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      walk(path);
    } else if (entry.endsWith(".tsx")) {
      checkFile(path);
    }
  }
}

function checkFile(path) {
  const src = readFileSync(path, "utf8");
  // crude but sufficient: a <button ... disabled ...> tag, checked for permission wording within
  // its own opening tag (title=/aria-label=), not the whole file — a disabled submit button with
  // an unrelated tooltip elsewhere in the file is not this anti-pattern.
  const tagRegex = /<button\b[^>]*>/gs;
  for (const match of src.matchAll(tagRegex)) {
    const tag = match[0];
    if (/\bdisabled\b/.test(tag) && PERMISSION_WORDS.test(tag)) {
      const line = src.slice(0, match.index).split("\n").length;
      violations.push(`${path}:${line}: disabled button carries a permission-related title/label`);
    }
  }
}

walk(ROOT);

if (violations.length > 0) {
  console.error("Permission-rendering contract violated:\n" + violations.join("\n"));
  process.exit(1);
}
console.log(`OK — no disabled-with-permission-reason buttons found.`);
