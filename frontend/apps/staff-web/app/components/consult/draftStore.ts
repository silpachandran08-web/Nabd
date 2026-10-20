// Local draft of an unsaved consultation note, keyed by queue entry id (DOC-014). The draft exists
// only while the server copy is behind: it is written when the note is dirty and deleted as soon as
// the server accepts a save, so clinical text does not linger on the device. Every call swallows
// storage errors (private mode, blocked storage) — the page works without it.
// FEF-005: records are AES-GCM ciphertext. The key is generated per page session, non-extractable and
// held only in memory, so a draft left behind by a crash/reload is unreadable (dropped on read) and
// signing out deletes the whole database. shortcut: a reload loses unsent drafts until a server-held
// key wrap exists (FEF-009); the page is told via takeLostDrafts().
export type NoteFields = { subjective: string; objective: string; assessment: string; plan: string; diagnosis: string };
export type LocalDraft = { entryId: string; note: NoteFields; baseUpdatedAt: string | null; savedAt: string };

const DB = "nabd-consult-drafts";
const STORE = "drafts";

let key: Promise<CryptoKey> | null = null;
let lost = 0;
const getKey = () => (key ??= crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]));
/** Drafts that were found but could not be decrypted since the last call (key lost on reload). */
export function takeLostDrafts(): number { const n = lost; lost = 0; return n; }

export async function seal(d: LocalDraft) {
  const iv = crypto.getRandomValues(new Uint8Array(new ArrayBuffer(12)));
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await getKey(), new TextEncoder().encode(JSON.stringify(d)));
  return { entryId: d.entryId, iv, ct };
}
export async function unseal(r: { iv: BufferSource; ct: ArrayBuffer }): Promise<LocalDraft | null> {
  try {
    return JSON.parse(new TextDecoder().decode(await crypto.subtle.decrypt({ name: "AES-GCM", iv: r.iv }, await getKey(), r.ct)));
  } catch {
    return null;
  }
}

/** Sign-out: drop the key and delete the whole database so nothing readable is left for the next user. */
export function clearDrafts(): Promise<void> {
  key = null;
  return new Promise((resolve) => {
    try {
      if (typeof indexedDB === "undefined") return resolve();
      const req = indexedDB.deleteDatabase(DB);
      req.onsuccess = req.onerror = req.onblocked = () => resolve();
    } catch {
      resolve();
    }
  });
}

function open(): Promise<IDBDatabase | null> {
  return new Promise((resolve) => {
    try {
      if (typeof indexedDB === "undefined") return resolve(null);
      const req = indexedDB.open(DB, 1);
      req.onupgradeneeded = () => req.result.createObjectStore(STORE, { keyPath: "entryId" });
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
}

async function run<T>(mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T | null> {
  const db = await open();
  if (!db) return null;
  return new Promise((resolve) => {
    try {
      const req = fn(db.transaction(STORE, mode).objectStore(STORE));
      req.onsuccess = () => { db.close(); resolve(req.result); };
      req.onerror = () => { db.close(); resolve(null); };
    } catch {
      db.close();
      resolve(null);
    }
  });
}

export async function readDraft(entryId: string): Promise<LocalDraft | null> {
  const r = await run<{ iv: BufferSource; ct: ArrayBuffer } | undefined>("readonly", (s) => s.get(entryId));
  if (!r) return null;
  const d = await unseal(r);
  if (!d) { lost++; await deleteDraft(entryId); }
  return d;
}
export async function writeDraft(d: LocalDraft) {
  try {
    const rec = await seal(d);
    return await run("readwrite", (s) => s.put(rec));
  } catch {
    return null; // no WebCrypto: no draft rather than a plaintext one
  }
}
export const deleteDraft = (entryId: string) => run("readwrite", (s) => s.delete(entryId));
