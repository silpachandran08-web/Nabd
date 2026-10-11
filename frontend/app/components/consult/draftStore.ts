// Local draft of an unsaved consultation note, keyed by queue entry id (DOC-014). The draft exists
// only while the server copy is behind: it is written when the note is dirty and deleted as soon as
// the server accepts a save, so clinical text does not linger on the device. Every call swallows
// storage errors (private mode, blocked storage) — the page works without it.
export type NoteFields = { subjective: string; objective: string; assessment: string; plan: string; diagnosis: string };
export type LocalDraft = { entryId: string; note: NoteFields; baseUpdatedAt: string | null; savedAt: string };

const DB = "nabd-consult-drafts";
const STORE = "drafts";

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

export const readDraft = (entryId: string) => run<LocalDraft | undefined>("readonly", (s) => s.get(entryId)).then((d) => d ?? null);
export const writeDraft = (d: LocalDraft) => run("readwrite", (s) => s.put(d));
export const deleteDraft = (entryId: string) => run("readwrite", (s) => s.delete(entryId));
