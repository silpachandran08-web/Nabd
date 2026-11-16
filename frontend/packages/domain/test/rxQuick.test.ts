// CLN-022: Rx pad quick actions (repeat last source, keyboard shortcuts).
import { expect, test } from "vitest";
import { repeatSource, rxShortcut } from "../src/index.ts";

const rx = (id: string, queueEntryId: string, status = "signed") => ({ id, queueEntryId, status });

test("repeat source: newest signed prescription from another visit, never this visit's own", () => {
  expect(repeatSource([rx("a", "today"), rx("b", "v2"), rx("c", "v1")], "today")?.id).toBe("b");
  expect(repeatSource([rx("d", "v3", "draft"), rx("b", "v2")], "today")?.id).toBe("b");
  expect(repeatSource([rx("a", "today")], "today")).toBeNull();
  expect(repeatSource([], "today")).toBeNull();
});

test("shortcuts: R repeats, 1-9 pick an existing chip, nothing while typing or with a modifier", () => {
  expect(rxShortcut({ key: "r", typing: false }, 0)).toEqual({ kind: "repeat" });
  expect(rxShortcut({ key: "R", typing: false }, 0)).toEqual({ kind: "repeat" });
  expect(rxShortcut({ key: "1", typing: false }, 3)).toEqual({ kind: "set", index: 0 });
  expect(rxShortcut({ key: "3", typing: false }, 3)).toEqual({ kind: "set", index: 2 });
  expect(rxShortcut({ key: "4", typing: false }, 3)).toBeNull();
  expect(rxShortcut({ key: "9", typing: false }, 12)).toEqual({ kind: "set", index: 8 });
  expect(rxShortcut({ key: "0", typing: false }, 12)).toBeNull();
  expect(rxShortcut({ key: "r", typing: true }, 3)).toBeNull();
  expect(rxShortcut({ key: "1", ctrlKey: true, typing: false }, 3)).toBeNull();
  expect(rxShortcut({ key: "r", metaKey: true, typing: false }, 3)).toBeNull();
  expect(rxShortcut({ key: "2", altKey: true, typing: false }, 3)).toBeNull();
  expect(rxShortcut({ key: "x", typing: false }, 3)).toBeNull();
});
