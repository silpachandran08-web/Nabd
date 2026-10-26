"use client";

import { useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { printCss, type Paper } from "./paper.ts";

/**
 * One printable sheet (`bare`: the content draws its own paper on screen). Mount it only on a print screen / dialog: its
 * @page rule applies to the whole print job. Browsers cannot print an "auto height" roll, so on 80 mm the sheet measures
 * itself (it is 72 mm wide on screen too) and asks for exactly that page height.
 * shortcut: measured once per size change, not at the moment of printing; upgrade if a slip's content changes while the print dialog is open.
 */
export function PrintSheet({ paper, label, bare, children }: { paper: Paper; label: string; bare?: boolean; children: ReactNode }) {
  const ref = useRef<HTMLElement>(null);
  const [heightMm, setHeightMm] = useState<number | undefined>(undefined);
  useLayoutEffect(() => {
    const el = ref.current;
    if (paper !== "80mm" || !el || typeof ResizeObserver === "undefined") return;
    const measure = () => setHeightMm(Math.ceil((el.getBoundingClientRect().height * 25.4) / 96) + 1);
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [paper]);
  return (
    <>
      <style>{printCss(paper, heightMm)}</style>
      <article ref={ref} className="nbp-sheet" data-paper={paper} data-bare={bare ? "" : undefined} aria-label={bare ? undefined : label}>{children}</article>
    </>
  );
}

/** Clinic block on top: name, phone and any registration numbers (GSTIN, clinic reg. no., prescriber reg. no.). */
export function PrintHeader({ clinic, phone, registrations = [] }: { clinic: string; phone?: string | null; registrations?: { label: string; value: string }[] }) {
  return (
    <header>
      <h2 className="nbp-h"><bdi>{clinic}</bdi></h2>
      {phone && <div>{phone}</div>}
      {registrations.map((r) => <div key={r.label}>{r.label}: {r.value}</div>)}
    </header>
  );
}

/** Slot for a second language (Arabic/Hindi): two columns, or stacked (the default on narrow paper). */
export function PrintBilingual({ layout = "stacked", primary, secondary, secondaryLang, secondaryDir }: {
  layout?: "columns" | "stacked"; primary: ReactNode; secondary?: ReactNode; secondaryLang?: string; secondaryDir?: "rtl" | "ltr";
}) {
  if (!secondary) return <>{primary}</>;
  return (
    <div className={layout === "columns" ? "nbp-cols" : undefined}>
      <div>{primary}</div>
      <div lang={secondaryLang} dir={secondaryDir}>{secondary}</div>
    </div>
  );
}

/** QR frame. The caller supplies the image (a data: URL): the kit generates nothing. */
export function PrintQr({ src, caption }: { src: string; caption?: string }) {
  return (
    <figure className="nbp-qr" style={{ margin: 0 }}>
      <img src={src} alt={caption ?? "QR code"} />
      {caption && <figcaption>{caption}</figcaption>}
    </figure>
  );
}

/** Label / amount rows; the last row can be marked strong (grand total). Amounts arrive already formatted. */
export function PrintTotals({ rows }: { rows: { label: string; value: string; strong?: boolean }[] }) {
  return (
    <table className="nbp-totals"><tbody>
      {rows.map((r) => <tr key={r.label} className={r.strong ? "nbp-strong" : undefined}><td>{r.label}</td><td>{r.value}</td></tr>)}
    </tbody></table>
  );
}

/** Free footer text. Page x of y is drawn by the page margin box (A4/A5), not here. */
export function PrintFooter({ children }: { children: ReactNode }) {
  return <footer className="nbp-foot">{children}</footer>;
}
