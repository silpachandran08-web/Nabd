// FEF-036: pseudo-localisation. Literal text becomes accented look-alikes and grows ~35%; ICU syntax is kept by working on the parsed AST.
// rtl=true wraps the text in RTL embedding marks so mirrored-layout bugs show up without a translator.
import { parse, TYPE, type MessageFormatElement } from "@formatjs/icu-messageformat-parser";
import { printAST } from "@formatjs/icu-messageformat-parser/printer.js";

const FROM = "abcdefghijklmnopqrstuvwxyz";
const TO = "àƀçðéƒĝĥîĵķļɱñöþƣŕšţûṽŵẋýž";
const accent = (s: string) => s.replace(/[a-z]/gi, (c) => { const m = TO[FROM.indexOf(c.toLowerCase())]; return c === c.toLowerCase() ? m : m.toUpperCase(); });

function walk(els: MessageFormatElement[]): MessageFormatElement[] {
  return els.map((e) => {
    if (e.type === TYPE.literal) return { ...e, value: accent(e.value) + "·".repeat(Math.ceil(e.value.length * 0.35)) };
    if (e.type === TYPE.plural || e.type === TYPE.select) {
      const options = Object.fromEntries(Object.entries(e.options).map(([k, v]) => [k, { value: walk(v.value) }]));
      return { ...e, options } as MessageFormatElement;
    }
    if (e.type === TYPE.tag) return { ...e, children: walk(e.children) };
    return e;
  });
}

export function pseudoMessage(msg: string, rtl = false): string {
  const body = `[${printAST(walk(parse(msg)))}]`;
  return rtl ? `‫${body}‬` : body;
}

/** Deep-map a catalogue (nested objects of strings) through pseudoMessage. */
export function pseudoCatalogue<T>(tree: T, rtl = false): T {
  if (typeof tree === "string") return pseudoMessage(tree, rtl) as T;
  if (tree && typeof tree === "object") return Object.fromEntries(Object.entries(tree).map(([k, v]) => [k, pseudoCatalogue(v, rtl)])) as T;
  return tree;
}
