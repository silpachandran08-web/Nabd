"use client";

import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { api, errorText } from "../../lib/api";
import { decodeAccessToken } from "../../lib/session";
import { usePermissions } from "../../lib/usePermissions";
import Composer from "./Composer";
import NotesDialog from "./NotesDialog";
import TemplatePicker from "./TemplatePicker";
import { FILTERS, authorLabel, canFreeText, isFailed, keyAction, oldestWaiting, previewText, sessionPill, tick, waitingChip, type FilterId } from "./inboxLogic";
import { useMessages, useThreads } from "./useInbox";
import styles from "./inbox.module.css";

const PHONE = "(max-width: 639px)";
const subscribePhone = (cb: () => void) => { const m = window.matchMedia(PHONE); m.addEventListener("change", cb); return () => m.removeEventListener("change", cb); };
const usePhone = () => useSyncExternalStore(subscribePhone, () => window.matchMedia(PHONE).matches, () => false);

function useMinute(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), 60_000); return () => clearInterval(id); }, []);
  return now;
}

type Assignee = { id: string; name: string };

/** Reception's WhatsApp inbox (prototype reception.messages): who is waiting and for how long, the 24-hour window, templates once it closes. Keys: J/K move, R reply, T templates. */
export default function InboxScreen() {
  const { permissions } = usePermissions();
  const canReply = permissions.includes("inbox:reply");
  const meId = useMemo(() => { try { return decodeAccessToken(localStorage.getItem("nabd_access_token") ?? "")?.sub ?? null; } catch { return null; } }, []);
  const phone = usePhone();
  const now = useMinute();
  const [filter, setFilter] = useState<FilterId>("all");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [view, setView] = useState<"list" | "thread">("list");
  const [dialog, setDialog] = useState<"templates" | "notes" | null>(null);
  const [notice, setNotice] = useState("");
  const [assignees, setAssignees] = useState<Assignee[]>([]);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const { threads, failed, reload } = useThreads(filter);
  const items = threads ?? [];
  const selected = items.find((t) => t.id === selectedId) ?? null;
  const { messages, failed: msgFailed, reload: reloadMessages } = useMessages(selectedId, reload);

  useEffect(() => { // first view: the oldest waiting thread is open (on a phone the list comes first)
    if (threads && !phone && !items.some((t) => t.id === selectedId)) setSelectedId(oldestWaiting(items)?.id ?? null); // eslint-disable-line react-hooks/set-state-in-effect
  }, [threads, phone]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!canReply) return;
    api.get<Assignee[]>("/inbox/assignees").then(setAssignees).catch(() => setAssignees([]));
  }, [canReply]);
  const staffName = useCallback((id: string) => assignees.find((a) => a.id === id)?.name, [assignees]);

  function open(id: string) { setSelectedId(id); setView("thread"); }
  const idx = items.findIndex((t) => t.id === selectedId);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const act = keyAction(e as unknown as Parameters<typeof keyAction>[0], dialog !== null);
      if (!act) return;
      if (act === "next" || act === "prev") {
        const next = items[Math.max(0, Math.min(items.length - 1, idx + (act === "next" ? 1 : -1)))];
        if (next) setSelectedId(next.id);
      } else if (!selected || !canReply) return;
      else if (act === "templates") setDialog("templates");
      else if (canFreeText(selected, Date.now())) inputRef.current?.focus();
      else setDialog("templates");
      e.preventDefault();
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  });
  useEffect(() => { document.getElementById(`thread-${selectedId}`)?.scrollIntoView?.({ block: "nearest" }); }, [selectedId]);

  async function act(path: string, body: unknown, fail: string) {
    try { await api.post(`/inbox/threads/${selected?.id}/${path}`, body); setNotice(""); await Promise.all([reload(), reloadMessages()]); } catch (e) { setNotice(errorText(e, fail)); }
  }
  const handover = (mode: "bot" | "human") => act("mode", { mode }, "Couldn't change who replies. Nothing changed.");

  const assignedLabel = selected?.assignedTo ? `Assigned to ${selected.assignedToName ?? staffName(selected.assignedTo) ?? (selected.assignedTo === meId ? "you" : "a colleague")}` : "Not assigned";

  return (
    <main className={styles.page}>
      <h1 className={styles.srOnly}>WhatsApp inbox</h1>
      {notice && <div className={styles.notice} role="status">{notice}</div>}
      <div className={styles.layout} data-view={view}>
        <section className={styles.listPane} aria-label="Conversations">
          <div className={styles.filters} role="group" aria-label="Filter conversations">
            {FILTERS.map((f) => (
              <button key={f.id} type="button" className={`${styles.filter} ${filter === f.id ? styles.filterOn : ""}`} aria-pressed={filter === f.id} onClick={() => setFilter(f.id)}>{f.label}</button>
            ))}
          </div>
          {failed && threads === null ? <ErrorState message="Couldn't load the conversations. Check your connection and try again." onRetry={() => void reload()} />
            : threads === null ? <Skeleton rows={6} label="Loading conversations" />
            : items.length === 0 ? <EmptyState icon="inbox" title={filter === "all" ? "No conversations yet." : "Nothing here."} hint="New WhatsApp messages from patients appear here by themselves." />
            : (
              <ul className={styles.threads} aria-label="Conversation list">
                {items.map((t) => {
                  const pill = sessionPill(t, now);
                  const wait = waitingChip(t.waitingMinutes);
                  return (
                    <li key={t.id} id={`thread-${t.id}`}>
                      <button type="button" className={`${styles.thread} ${t.id === selectedId ? styles.selected : ""}`} aria-current={t.id === selectedId ? "true" : undefined} onClick={() => open(t.id)}>
                        <span className={styles.threadTop}>
                          <span className={styles.name}>{t.displayName}</span>
                          {t.unread > 0 && <span className={styles.unread} aria-label={`${t.unread} unread`}>{t.unread}</span>}
                        </span>
                        <span className={styles.preview}>{previewText(t)}</span>
                        <span className={styles.meta}>
                          <span className={`${styles.pill} ${styles[`pill_${pill.kind}`]}`}>{pill.label}</span>
                          {wait && <span className={styles.wait}>{wait}</span>}
                          {t.mode === "bot" && <span className={styles.pill}>Bot</span>}
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
        </section>

        <section className={styles.threadPane} aria-label="Conversation">
          {!selected ? <EmptyState icon="inbox" title="Choose a conversation." hint="Pick one from the list. Keys: J and K move, R replies, T opens templates." /> : (
            <>
              <header className={styles.threadHead}>
                <button type="button" className={styles.back} onClick={() => setView("list")}><span aria-hidden="true">‹</span> Back</button>
                <div className={styles.headMain}>
                  <h2 className={styles.title}>{selected.displayName}</h2>
                  <span className={`${styles.pill} ${styles[`pill_${sessionPill(selected, now).kind}`]}`}>{sessionPill(selected, now).label}</span>
                  {selected.clinical && <span className={styles.pill}>Clinical</span>}
                </div>
                {canReply && (
                  <div className={styles.headTools}>
                    <span className={styles.assigned}>{assignedLabel}</span>
                    {selected.assignedTo !== meId && <button type="button" className={styles.secondary} onClick={() => void act("assign", { staffId: meId }, "Couldn't assign the chat.")}>Assign to me</button>}
                    {assignees.length > 0 && (
                      <select aria-label="Assign chat" className={styles.select} value={selected.assignedTo ?? ""} onChange={(e) => void act("assign", { staffId: e.target.value || null }, "Couldn't assign the chat.")}>
                        <option value="">Unassigned</option>
                        {assignees.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
                      </select>
                    )}
                    <button type="button" className={styles.secondary} onClick={() => setDialog("notes")}>Call notes</button>
                  </div>
                )}
              </header>
              {canReply && selected.mode === "bot" && (
                <div className={styles.botbar}>Nabd assistant is replying to this chat.
                  <button type="button" className={styles.secondary} onClick={() => void handover("human")}>Take over</button>
                </div>
              )}
              {canReply && selected.mode === "human" && (
                <div className={styles.botbar}>You are replying.
                  <button type="button" className={styles.secondary} onClick={() => void handover("bot")}>Hand back to bot</button>
                </div>
              )}
              {canReply && selected.clinicalSuggested && !selected.clinical && (
                <div className={styles.botbar}>This may be a clinical message. Tag it so the nurse sees it.
                  <button type="button" className={styles.secondary} onClick={() => void act("clinical", { clinical: true }, "Couldn't tag the chat.")}>Tag as clinical</button>
                </div>
              )}
              {canReply && selected.clinical && (
                <div className={styles.botbar}>Tagged clinical: the nurse inbox lists this chat.
                  <button type="button" className={styles.secondary} onClick={() => void act("clinical", { clinical: false }, "Couldn't remove the tag.")}>Remove tag</button>
                </div>
              )}
              {msgFailed && messages === null ? <ErrorState message="Couldn't load this conversation." onRetry={() => void reloadMessages()} />
                : messages === null ? <Skeleton rows={4} label="Loading messages" />
                : messages.length === 0 ? <EmptyState icon="inbox" title="No messages yet." />
                : (
                  <ol className={styles.messages} aria-label="Messages">
                    {messages.map((m) => {
                      const out = m.direction === "out";
                      const t = tick(m.status);
                      return (
                        <li key={m.id} className={`${styles.bubble} ${out ? styles.out : styles.in}`}>
                          <span className={styles.author}>{authorLabel(m, meId, staffName)}</span>
                          <span className={styles.text}>{m.text ?? (m.templateName ? `Template sent: ${m.templateName}` : "Attachment")}</span>
                          <span className={styles.stamp}>
                            {new Date(m.at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
                            {out && <span className={styles.tick} aria-label={t.label}>{t.glyph}</span>}
                          </span>
                          {out && isFailed(m.status) && (
                            <span className={styles.failed} role="alert">Not delivered.
                              {canReply && m.text && canFreeText(selected, now) && <button type="button" className={styles.secondary} onClick={() => void act("replies", { text: m.text }, "Couldn't resend. Nothing was sent.")}>Retry</button>}
                            </span>
                          )}
                        </li>
                      );
                    })}
                  </ol>
                )}
              {canReply && (canFreeText(selected, now)
                ? <Composer key={selected.id} thread={selected} inputRef={inputRef} onHandover={() => handover("human")} onSent={() => { void reload(); void reloadMessages(); }} onWindowClosed={() => void reload()} />
                : selected.optedOut ? <p className={styles.hint}>This patient opted out of messages. You can&apos;t message them here.</p>
                : <div className={styles.closedBar}><span>The 24-hour window is closed. Only an approved template can reach this patient.</span>
                    <button type="button" className={styles.primary} onClick={() => setDialog("templates")}>Choose template</button></div>)}
            </>
          )}
        </section>
      </div>
      {dialog === "templates" && selected && <TemplatePicker threadId={selected.id} language={selected.language} onClose={() => setDialog(null)} onSent={() => { setDialog(null); setNotice("Template sent."); void reload(); void reloadMessages(); }} />}
      {dialog === "notes" && selected && <NotesDialog threadId={selected.id} onClose={() => setDialog(null)} />}
    </main>
  );
}
