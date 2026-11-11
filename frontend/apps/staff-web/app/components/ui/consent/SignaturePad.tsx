"use client";

import { useEffect, useRef } from "react";
import { PAD_H, PAD_W, type Point } from "./consentModel";
import c from "./procedureConsent.module.css";

// CLN-014: finger / stylus / mouse signature. Pointer events in the canvas's own 600 x 200 box (so a 7-inch tablet and a
// desktop give the same coordinates); touch-action: none stops the page scrolling while signing.
export default function SignaturePad({ label, strokes, onChange }: { label: string; strokes: Point[][]; onChange: (s: Point[][]) => void }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const drawing = useRef<Point[] | null>(null);
  const latest = useRef(strokes);
  useEffect(() => { latest.current = strokes; }, [strokes]);

  useEffect(() => {
    const cv = ref.current;
    const g = cv?.getContext("2d");
    if (!cv || !g) return;
    g.clearRect(0, 0, PAD_W, PAD_H);
    g.lineWidth = 2.5; g.lineCap = "round"; g.lineJoin = "round";
    g.strokeStyle = getComputedStyle(cv).color;
    for (const s of strokes) {
      g.beginPath();
      s.forEach(([x, y], i) => (i === 0 ? g.moveTo(x, y) : g.lineTo(x, y)));
      if (s.length === 1) g.lineTo(s[0][0] + 0.5, s[0][1]);
      g.stroke();
    }
  }, [strokes]);

  const at = (e: React.PointerEvent<HTMLCanvasElement>): Point => {
    const r = e.currentTarget.getBoundingClientRect();
    return [((e.clientX - r.left) / r.width) * PAD_W, ((e.clientY - r.top) / r.height) * PAD_H];
  };

  return (
    <canvas ref={ref} className={c.pad} width={PAD_W} height={PAD_H} aria-label={label}
      onPointerDown={(e) => { e.currentTarget.setPointerCapture(e.pointerId); drawing.current = [at(e)]; onChange([...latest.current, drawing.current]); }}
      onPointerMove={(e) => { if (!drawing.current) return; drawing.current.push(at(e)); onChange([...latest.current.slice(0, -1), [...drawing.current]]); }}
      onPointerUp={() => { drawing.current = null; }}
      onPointerCancel={() => { drawing.current = null; }} />
  );
}
