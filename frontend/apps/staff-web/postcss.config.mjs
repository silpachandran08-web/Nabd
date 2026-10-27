// FEF-022: @media (--bp-phone) etc. Every stylesheet gets the generated @custom-media definitions prepended, then
// postcss-custom-media resolves and drops them (the plugin only sees definitions in the same file).
import { readFileSync } from "node:fs";
import postcss from "postcss";
import customMedia from "postcss-custom-media";

const defs = readFileSync(new URL("../../packages/tokens/dist/custom-media.css", import.meta.url), "utf8");
const inject = () => ({ postcssPlugin: "nabd-inject-custom-media", Once(root) { root.prepend(...postcss.parse(defs).nodes); } });
inject.postcss = true;

export default { plugins: [inject(), customMedia()] };
