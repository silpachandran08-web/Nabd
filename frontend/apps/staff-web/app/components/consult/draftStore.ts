// Local draft of an unsaved consult section, keyed by queue entry id and section (DOC-014, FEF-035). The draft exists
// only while the server copy is behind: it is written when the section is dirty and deleted as soon as
// the server accepts a save (or the form is submitted), so clinical text does not linger on the device. Every call
// swallows storage errors (private mode, blocked storage) — the page works without it.
// FEF-005: records are AES-GCM ciphertext. The key is generated per browser session, non-extractable once imported,
// and signing out deletes the key and the whole database, so nothing readable is left for the next user.
// FEF-035: the raw key bytes are kept in sessionStorage (per tab, gone with the tab and on sign-out) so a crash /
// reload of the same tab restores the drafts. shortcut: key material in sessionStorage, move to a server-held key
// wrap when one exists. A new tab has no key: its drafts are unreadable (dropped on read, counted by takeLostDrafts()).
export type NoteFields = { subjective: string; objective: string; assessment: string; plan: string; diagnosis: string };
/** "note" is the SOAP note (key = entryId, unchanged from DOC-014); every other section is keyed entryId#section. */
export type DraftSection = "note" | "rx" | "followUp" | "orders.admin" | "orders.proc" | "allergy";
export type LocalDraft<T = unknown> = { entryId: string; section?: DraftSection; data: T; baseUpdatedAt: string | null; savedAt: string };

const DB = "nabd-consult-drafts";
const STORE = "drafts";
const KEY_SLOT = "nabd_draft_key";
const ALGO = { name: "AES-GCM", length: 256 };

let key: Promise<CryptoKey> | null = null;
let lost = 0;
async function loadKey(): Promise<CryptoKey> {
  try {
    const hex = sessionStorage.getItem(KEY_SLOT);
    if (hex && /^[0-9a-f]{64}$/.test(hex)) return await crypto.subtle.importKey("raw", Uint8Array.from(hex.match(/../g)!, (h) => parseInt(h, 16)), ALGO, false, ["encrypt", "decrypt"]);
  } catch { /* no sessionStorage or a corrupt slot: fresh key below */ }
  const raw = crypto.getRandomValues(new Uint8Array(new ArrayBuffer(32)));
  try { sessionStorage.setItem(KEY_SLOT, Array.from(raw, (b) => b.toString(16).padStart(2, "0")).join("")); } catch { /* blocked storage: drafts last until reload */ }
  return crypto.subtle.importKey("raw", raw, ALGO, false, ["encrypt", "decrypt"]);
}
const getKey = () => (key ??= loadKey());
/** Drafts that were found but could not be decrypted since the last call (key lost: new tab). */
export function takeLostDrafts(): number { const n = lost; lost = 0; return n; }

export const draftKey = (entryId: string, section: DraftSection = "note") => (section === "note" ? entryId : `${entryId}#${section}`);

export async function seal(d: LocalDraft) {
  const iv = crypto.getRandomValues(new Uint8Array(new ArrayBuffer(12)));
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await getKey(), new TextEncoder().encode(JSON.stringify(d)));
  return { entryId: draftKey(d.entryId, d.section), iv, ct };
}
export async function unseal<T = unknown>(r: { iv: BufferSource; ct: ArrayBuffer }): Promise<LocalDraft<T> | null> {
  try {
    return JSON.parse(new TextDecoder().decode(await crypto.subtle.decrypt({ name: "AES-GCM", iv: r.iv }, await getKey(), r.ct)));
  } catch {
    return null;
  }
}

/** Sign-out: drop the key and delete the whole database so nothing readable is left for the next user. */
export function clearDrafts(): Promise<void> {
  key = null;
  try { sessionStorage.removeItem(KEY_SLOT); } catch { /* ignore */ }
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

export async function readDraft<T = unknown>(entryId: string, section: DraftSection = "note"): Promise<LocalDraft<T> | null> {
  const r = await run<{ iv: BufferSource; ct: ArrayBuffer } | undefined>("readonly", (s) => s.get(draftKey(entryId, section)));
  if (!r) return null;
  const d = await unseal<T>(r);
  if (!d) { lost++; await deleteDraft(entryId, section); }
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
export const deleteDraft = (entryId: string, section: DraftSection = "note") => run("readwrite", (s) => s.delete(draftKey(entryId, section)));
/** After the visit is signed: every section's draft for that visit (keys entryId and entryId#...). */
export const deleteVisitDrafts = (entryId: string) => run("readwrite", (s) => s.delete(IDBKeyRange.bound(entryId, `${entryId}#￿`)));
