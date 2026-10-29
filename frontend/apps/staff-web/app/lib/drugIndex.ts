// CLN-020: per-doctor drug index kept on the device so most keystrokes of the drug typeahead never reach the server.
// GET /clinical/drugs/client-index returns gzip JSON {v, master, items:[[drugId, display, generic, brand, strength, form,
// schedule[]]]} ordered by rank (favourites, then this doctor's and clinic's usage, then A-Z) with an ETag. The copy is
// stored in IndexedDB keyed by tenant + staff id, revalidated with If-None-Match once per page load (304 = no body), and
// deleted on sign-out (T-029). Drug ids and names only: no patient data. Every storage error falls back to the server search.
import type { DrugSuggestion } from "../components/consult/shared.ts";

export type IndexDoc = { rev: string; entries: IndexEntry[] };
export type IndexEntry = { id: string; display: string; generic: string | null; brand: string; strength: string | null; form: string | null; schedule: string[] };
type Stored = { key: string; rev: string; at: number; entries: IndexEntry[] };
/** One conditional GET of the index: resolves the gzip body, or null for 304 (not modified). Wired to the typed api client. */
export type IndexGet = (ifNoneMatch: string | null) => Promise<ArrayBuffer | null>;

const DB = "nabd-drug-index";
const STORE = "kv";

/** Raw JSON of the index -> rev + entries. A body that is not the expected shape yields null (the caller keeps what it has). */
export function parseIndex(doc: unknown): IndexDoc | null {
  const items = (doc as { items?: unknown } | null)?.items;
  const rev = (doc as { rev?: unknown } | null)?.rev;
  if (!Array.isArray(items) || typeof rev !== "string") return null;
  const out: IndexEntry[] = [];
  for (const r of items) {
    if (!Array.isArray(r) || typeof r[0] !== "string" || typeof r[1] !== "string") return null;
    out.push({ id: r[0], display: r[1], generic: r[2] ?? null, brand: String(r[3] ?? ""), strength: r[4] ?? null, form: r[5] ?? null, schedule: Array.isArray(r[6]) ? r[6] : [] });
  }
  return { rev, entries: out };
}

/** Every word of the query must start a word of the row (brand, generic, strength, form); the entry order is the ranking. */
export function searchIndex(entries: IndexEntry[], query: string, limit = 8): DrugSuggestion[] {
  const words = query.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean);
  if (words.length === 0) return [];
  const out: DrugSuggestion[] = [];
  for (const e of entries) {
    const hay = `${e.brand} ${e.generic ?? ""} ${e.strength ?? ""} ${e.form ?? ""}`.toLowerCase().split(/[^\p{L}\p{N}]+/u);
    if (words.every((w) => hay.some((h) => h.startsWith(w)))) {
      out.push({
        name: e.display, display: e.display, source: "master", code: null, inStock: null, prescriptionOnly: e.schedule.length > 0 ? true : null,
        drugId: e.id, generic: e.generic, brand: e.brand, strength: e.strength, form: e.form,
      });
      if (out.length >= limit) break;
    }
  }
  return out;
}

function idb<T>(run: (st: IDBObjectStore) => IDBRequest<T>, mode: IDBTransactionMode): Promise<T | null> {
  return new Promise((resolve) => {
    try {
      if (typeof indexedDB === "undefined") return resolve(null);
      const open = indexedDB.open(DB, 1);
      open.onupgradeneeded = () => open.result.createObjectStore(STORE);
      open.onerror = () => resolve(null);
      open.onsuccess = () => {
        const db = open.result;
        const tx = db.transaction(STORE, mode);
        const req = run(tx.objectStore(STORE));
        tx.oncomplete = () => { db.close(); resolve(req.result ?? null); };
        tx.onerror = tx.onabort = () => { db.close(); resolve(null); };
      };
    } catch { resolve(null); }
  });
}

/** Sign-out and session end: delete the whole database. */
export function clearDrugIndex(): Promise<void> {
  memo = null;
  return new Promise((resolve) => {
    try {
      if (typeof indexedDB === "undefined") return resolve();
      const req = indexedDB.deleteDatabase(DB);
      req.onsuccess = req.onerror = req.onblocked = () => resolve();
    } catch { resolve(); }
  });
}

async function inflate(buf: ArrayBuffer): Promise<unknown> {
  const stream = new Blob([buf]).stream().pipeThrough(new DecompressionStream("gzip"));
  return JSON.parse(await new Response(stream).text());
}

let memo: { key: string; entries: Promise<IndexEntry[] | null> } | null = null;

/** The index for this tenant + doctor: the stored copy at once, revalidated against the server (one conditional GET per
 * page load; 200 replaces the copy, 304 keeps it). null = no index available (server search is used). */
export function loadDrugIndex(get: IndexGet, tenantId: string, staffId: string): Promise<IndexEntry[] | null> {
  const key = `${tenantId}:${staffId}`;
  if (memo?.key === key) return memo.entries;
  const entries = (async () => {
    const stored = await idb<Stored>((st) => st.get(key) as IDBRequest<Stored>, "readonly");
    const mine = stored && stored.key === key && Array.isArray(stored.entries) ? stored : null;
    try {
      const body = await get(mine ? `"${mine.rev}"` : null);
      if (body === null && mine) return mine.entries;
      if (body) {
        const parsed = parseIndex(await inflate(body));
        if (parsed) {
          await idb((st) => st.put({ key, rev: parsed.rev, at: Date.now(), entries: parsed.entries } satisfies Stored, key), "readwrite");
          return parsed.entries;
        }
      }
    } catch { /* offline, 403 or blocked storage: fall through to the stored copy */ }
    return mine ? mine.entries : null;
  })();
  memo = { key, entries };
  void entries.then((r) => { if (!r && memo?.key === key) memo = null; }); // a failed load is retried on the next focus
  return entries;
}
