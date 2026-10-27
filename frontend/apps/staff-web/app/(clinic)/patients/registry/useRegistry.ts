import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError } from "../../../lib/api";
import type { Registry } from "../patientsModel";

/** FEF-031: registry list state, live search (first load immediate, typing debounced), filter, keyset paging (OPS-008). */
export function useRegistry(initialQ: string) {
  const [registry, setRegistry] = useState<Registry | null>(null);
  const [filter, setFilter] = useState("all");
  const [moreState, setMoreState] = useState<"idle" | "loading" | "error">("idle");
  const [q, setQ] = useState(initialQ);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const loadSeq = useRef(0);
  const moreRef = useRef<HTMLDivElement | null>(null);

  const load = useCallback(
    async (query: string, which: string) => {
      setError(null); // `loading` is only the first load — searching/filtering keeps the table on screen
      const seq = ++loadSeq.current;
      try {
        const params = new URLSearchParams({ filter: which });
        if (query.trim()) params.set("q", query.trim());
        const body = await api.get<Registry>(`/patients/registry?${params}`);
        if (seq === loadSeq.current) { setRegistry(body); setMoreState("idle"); } // else a newer search is in flight
      } catch (e) {
        if (seq !== loadSeq.current) return;
        if (e instanceof ApiError && e.status === 403) setForbidden(true);
        else setError(e instanceof ApiError ? "Couldn't load patients. Try again." : "Couldn't reach the server. Check your connection and try again.");
      } finally {
        if (seq === loadSeq.current) setLoading(false);
      }
    },
    []
  );

  // The next keyset page of the same filter + search, appended (infinite scroll; the button is the keyboard path).
  const nextCursor = registry?.page?.nextCursor ?? null;
  const loadMore = useCallback(async () => {
    if (!nextCursor || moreState === "loading") return;
    const seq = loadSeq.current;
    setMoreState("loading");
    try {
      const params = new URLSearchParams({ filter, cursor: nextCursor });
      if (q.trim()) params.set("q", q.trim());
      const body = await api.get<Registry>(`/patients/registry?${params}`);
      if (seq !== loadSeq.current) return; // filter or search changed meanwhile
      setRegistry((prev) => prev && { ...body, rows: [...prev.rows, ...body.rows] });
      setMoreState("idle");
    } catch {
      if (seq === loadSeq.current) setMoreState("error");
    }
  }, [filter, q, nextCursor, moreState]);

  useEffect(() => {
    const el = moreRef.current;
    if (!el || !nextCursor || moreState !== "idle" || typeof IntersectionObserver === "undefined") return;
    const io = new IntersectionObserver((entries) => { if (entries.some((e) => e.isIntersecting)) void loadMore(); });
    io.observe(el);
    return () => io.disconnect();
  }, [loadMore, nextCursor, moreState]);

  // Live search: first load is immediate, typing is debounced; filter changes reload straight away.
  const firstLoad = useRef(true);
  useEffect(() => {
    const delay = firstLoad.current ? 0 : 300;
    firstLoad.current = false;
    const t = setTimeout(() => void load(q, filter), delay);
    return () => clearTimeout(t);
  }, [load, q, filter]);

  return { registry, filter, setFilter, q, setQ, loading, error, forbidden, moreState, moreRef, nextCursor, load, loadMore };
}
