"use client";

// ACC-023: the waiting-room display. Read-only: tokens only, never a name or a phone number; it takes no input from patients.
// A pairing code (front desk: Waiting-room display) gives this browser a device token kept in localStorage, never in the URL.
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import styles from "./display.module.css";
import { isRtl, liveness, parseSse, tokenText, wordsFor, type Liveness, type Projection } from "./displayLogic";

const BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
const TOKEN_KEY = "nabd_display_token";

function readToken(): string | null { try { return localStorage.getItem(TOKEN_KEY); } catch { return null; } }
function writeToken(t: string | null) { try { if (t) localStorage.setItem(TOKEN_KEY, t); else localStorage.removeItem(TOKEN_KEY); } catch { /* private mode: pairs again after a reload */ } }

export default function DisplayPage() {
  return <Suspense fallback={null}><Display /></Suspense>;
}

function Display() {
  const params = useSearchParams();
  const clinic = params.get("clinic") ?? "";
  const [token, setToken] = useState<string | null | undefined>(undefined); // undefined = not read yet
  useEffect(() => { void Promise.resolve().then(() => setToken(readToken())); }, []);
  if (token === undefined) return <main className={styles.screen} />;
  return token ? <Screen token={token} onUnpaired={() => { writeToken(null); setToken(null); }} /> : <Pairing clinic={clinic} onPaired={(t) => { writeToken(t); setToken(t); }} />;
}

function Pairing({ clinic, onPaired }: { clinic: string; onPaired: (token: string) => void }) {
  const [slug, setSlug] = useState(clinic);
  const [code, setCode] = useState("");
  const [msg, setMsg] = useState<"" | "wrong" | "locked" | "net">("");
  const [busy, setBusy] = useState(false);
  const w = wordsFor("en");
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setMsg("");
    try {
      const r = await fetch(`${BASE}/display/pair`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ clinic: slug.trim(), code }) });
      if (r.ok) { const b: { deviceToken: string } = await r.json(); onPaired(b.deviceToken); return; }
      setMsg(r.status === 429 ? "locked" : "wrong");
    } catch { setMsg("net"); } finally { setBusy(false); }
  }
  return (
    <main className={styles.screen}>
      <form className={styles.pair} onSubmit={submit}>
        <h1 className={styles.pairTitle}>{w.pairTitle}</h1>
        <p>{w.pairHint}</p>
        {!clinic && <input className={styles.pairInput} aria-label="Clinic" placeholder="clinic" value={slug} onChange={(e) => setSlug(e.target.value)} />}
        <input className={styles.pairInput} aria-label="Pairing code" inputMode="numeric" autoComplete="off" maxLength={6} pattern="\d{6}" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} />
        <button type="submit" className={styles.pairBtn} disabled={busy || code.length !== 6 || !slug.trim()}>{w.pair}</button>
        <p role="alert" className={styles.pairErr}>{msg === "wrong" ? w.wrong : msg === "locked" ? w.locked : msg === "net" ? w.lost : ""}</p>
      </form>
    </main>
  );
}

function Screen({ token, onUnpaired }: { token: string; onUnpaired: () => void }) {
  const [proj, setProj] = useState<Projection | null>(null);
  const [state, setState] = useState<Liveness>("live");
  const lastFrame = useRef(Date.now());

  const run = useCallback(async (signal: AbortSignal) => {
    let wait = 1000;
    while (!signal.aborted) {
      try {
        const r = await fetch(`${BASE}/display/stream`, { headers: { Authorization: `Display ${token}`, Accept: "text/event-stream" }, signal, cache: "no-store" });
        if (r.status === 401) { onUnpaired(); return; }
        if (r.ok && r.body) {
          wait = 1000;
          const reader = r.body.getReader();
          const dec = new TextDecoder();
          let buf = "";
          for (;;) {
            const { value, done } = await reader.read();
            if (done) break;
            buf += dec.decode(value, { stream: true });
            const { frames, rest } = parseSse(buf);
            buf = rest;
            for (const f of frames) {
              lastFrame.current = Date.now();
              if (f.event === "revoked") { onUnpaired(); return; }
              if (f.event === "projection") setProj(JSON.parse(f.data) as Projection);
            }
          }
        }
      } catch { if (signal.aborted) return; }
      await new Promise((res) => setTimeout(res, wait)); // reconnects by itself, backing off to 10 s
      wait = Math.min(wait * 2, 10_000);
    }
  }, [token, onUnpaired]);

  useEffect(() => {
    const ac = new AbortController();
    void Promise.resolve().then(() => run(ac.signal));
    const t = setInterval(() => setState(liveness(Date.now() - lastFrame.current)), 1000);
    return () => { ac.abort(); clearInterval(t); };
  }, [run]);

  const langs = proj?.languages?.length ? proj.languages : ["en"];
  const doctors = proj?.doctors ?? [];
  return (
    <main className={styles.screen} data-state={state}>
      {state !== "live" && (
        <div role="status" className={state === "lost" ? styles.lost : styles.stale} data-testid="liveness">
          {langs.map((l) => <span key={l} dir={isRtl(l) ? "rtl" : "ltr"} lang={l}>{state === "lost" ? wordsFor(l).lost : wordsFor(l).stale}</span>)}
        </div>
      )}
      {!proj ? <p className={styles.none}>...</p> : doctors.length === 0 ? (
        <p className={styles.none}>{langs.map((l) => <span key={l} dir={isRtl(l) ? "rtl" : "ltr"} lang={l}>{wordsFor(l).none}</span>)}</p>
      ) : (
        <div className={styles.grid} data-count={doctors.length}>
          {doctors.map((d, i) => (
            <section key={`${d.name}-${i}`} className={styles.card} aria-label={d.name}>
              <h2 className={styles.doctor}>{d.name}</h2>
              <div className={styles.label}>{langs.map((l) => <span key={l} dir={isRtl(l) ? "rtl" : "ltr"} lang={l}>{wordsFor(l).now}</span>)}</div>
              <div className={styles.now} data-testid="now-serving">{d.nowServing ? tokenText(d.nowServing) : "-"}</div>
              <div className={styles.label}>{langs.map((l) => <span key={l} dir={isRtl(l) ? "rtl" : "ltr"} lang={l}>{wordsFor(l).next}</span>)}</div>
              <div className={styles.next} data-testid="next-tokens">{d.next.length ? d.next.map((n) => <span key={n}>{tokenText(n)}</span>) : <span>-</span>}</div>
              {d.delayMinutes ? <div className={styles.delay}>{langs.map((l) => <span key={l} dir={isRtl(l) ? "rtl" : "ltr"} lang={l}>{wordsFor(l).late(d.delayMinutes as number)}</span>)}</div> : null}
            </section>
          ))}
        </div>
      )}
    </main>
  );
}
