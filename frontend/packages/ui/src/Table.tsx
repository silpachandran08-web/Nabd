"use client";

import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { Icon } from "./Icon.tsx";

export type Column<T> = { key: string; header: string; render: (row: T) => ReactNode; sortValue?: (row: T) => string | number; cardLabel?: string };

/** Rows to mount for a fixed-height window (pure, unit-tested). */
export function windowRange(scrollTop: number, viewport: number, rowH: number, total: number, overscan = 6): [number, number] {
  const start = Math.max(0, Math.floor(scrollTop / rowH) - overscan);
  const end = Math.min(total, Math.ceil((scrollTop + viewport) / rowH) + overscan);
  return [start, Math.max(start, end)];
}
/** Row index after an arrow/Home/End/Page key; -1 when the key is not navigation (pure, unit-tested). */
export function nextRow(key: string, i: number, total: number, page = 10): number {
  if (total === 0) return -1;
  switch (key) {
    case "ArrowDown": return Math.min(total - 1, i + 1);
    case "ArrowUp": return Math.max(0, i - 1);
    case "Home": return 0;
    case "End": return total - 1;
    case "PageDown": return Math.min(total - 1, i + page);
    case "PageUp": return Math.max(0, i - page);
    default: return -1;
  }
}
export const VIRTUALISE_ABOVE = 300;
const ROW_H = 48;
const VIEW_H = 480;

// Sortable table. Under 768 px (CSS) each row becomes a card: cells carry data-label. A row is a button-like
// row (tabindex, Enter/Space) when onRowActivate is given; Arrow/Home/End/Page keys move between rows.
// Above 300 rows only a window of rows is mounted (fixed row height; the card layout is not virtualised: shortcut, cards vary in height).
export function Table<T>({ caption, columns, rows, rowKey, onRowActivate, virtualiseAbove = VIRTUALISE_ABOVE, empty }: {
  caption: string; columns: Column<T>[]; rows: T[]; rowKey: (r: T) => string; onRowActivate?: (r: T) => void; virtualiseAbove?: number; empty?: ReactNode;
}) {
  const [sort, setSort] = useState<{ key: string; dir: 1 | -1 } | null>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [active, setActive] = useState(0);
  const box = useRef<HTMLDivElement>(null);
  const sorted = (() => {
    const col = columns.find((c) => c.key === sort?.key);
    if (!sort || !col?.sortValue) return rows;
    const v = col.sortValue;
    return [...rows].sort((a, b) => (v(a) < v(b) ? -sort.dir : v(a) > v(b) ? sort.dir : 0));
  })();
  const virtual = sorted.length > virtualiseAbove;
  const [from, to] = virtual ? windowRange(scrollTop, VIEW_H, ROW_H, sorted.length) : [0, sorted.length];
  const shown = sorted.slice(from, to);
  const pending = useRef<number | null>(null);

  useEffect(() => {
    if (pending.current === null) return;
    const el = box.current?.querySelector<HTMLElement>(`[data-row="${pending.current}"]`);
    pending.current = null;
    el?.focus();
  });

  function onKey(e: KeyboardEvent<HTMLElement>, i: number, r: T) {
    if (onRowActivate && (e.key === "Enter" || e.key === " ") && e.target === e.currentTarget) { e.preventDefault(); onRowActivate(r); return; }
    const n = nextRow(e.key, i, sorted.length);
    if (n < 0 || e.target !== e.currentTarget) return;
    e.preventDefault();
    setActive(n);
    pending.current = n;
    if (virtual && box.current) {
      const top = n * ROW_H;
      if (top < box.current.scrollTop || top > box.current.scrollTop + VIEW_H - ROW_H) { box.current.scrollTop = top; setScrollTop(top); }
    }
  }

  if (rows.length === 0 && empty) return <>{empty}</>;
  return (
    <div ref={box} className="nbu-tablewrap" data-virtual={virtual ? "" : undefined} style={virtual ? { maxBlockSize: VIEW_H, overflowY: "auto" } : undefined} onScroll={virtual ? (e) => setScrollTop(e.currentTarget.scrollTop) : undefined}>
      <div role="table" aria-label={caption} aria-rowcount={sorted.length + 1} className="nbu-table">
        <div role="rowgroup" className="nbu-thead">
          <div role="row" className="nbu-tr nbu-tr-head">
            {columns.map((c) => (
              <div key={c.key} role="columnheader" className="nbu-th" aria-sort={sort?.key === c.key ? (sort.dir === 1 ? "ascending" : "descending") : c.sortValue ? "none" : undefined}>
                {c.sortValue ? (
                  <button type="button" className="nbu-sortbtn" onClick={() => setSort((s) => (s?.key === c.key ? (s.dir === 1 ? { key: c.key, dir: -1 } : null) : { key: c.key, dir: 1 }))}>
                    {c.header}{sort?.key === c.key && <Icon name={sort.dir === 1 ? "arrowUp" : "arrowDown"} size={12} />}
                  </button>
                ) : c.header}
              </div>
            ))}
          </div>
        </div>
        <div role="rowgroup" style={virtual ? { paddingBlockStart: from * ROW_H, paddingBlockEnd: (sorted.length - to) * ROW_H } : undefined}>
          {shown.map((r, k) => {
            const i = from + k;
            return (
              <div key={rowKey(r)} role="row" aria-rowindex={i + 2} data-row={i} className={`nbu-tr${onRowActivate ? " nbu-tr-link" : ""}`}
                tabIndex={onRowActivate ? (i === active ? 0 : -1) : undefined} onKeyDown={(e) => onKey(e, i, r)} onClick={onRowActivate ? () => onRowActivate(r) : undefined} style={virtual ? { blockSize: ROW_H } : undefined}>
                {columns.map((c) => <div key={c.key} role="cell" className="nbu-td" data-label={c.cardLabel ?? c.header}>{c.render(r)}</div>)}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
