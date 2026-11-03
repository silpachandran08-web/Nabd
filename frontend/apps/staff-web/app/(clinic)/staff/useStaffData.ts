"use client";

// FEF-032: the staff list of Staff & Access (first page, Load more) plus the side reads; moved unchanged from the old page.
import { useCallback, useEffect, useState } from "react";
import { goLogin } from "../../lib/apiClient";
import { api, ApiError, reasonText } from "../../lib/api";
import { isOwner } from "../../lib/session";
import { decodePermissions, type Staff, type StaffPage } from "./staffModel";
import { useStaffSide } from "./useStaffSide";

export function useStaffData() {
  const side = useStaffSide();
  const { loadSide } = side;
  const [permissions, setPermissions] = useState<string[]>([]);
  const [staff, setStaff] = useState<Staff[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [owner, setOwner] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    setForbidden(false);
    try {
      const token = localStorage.getItem("nabd_access_token");
      if (!token) {
        goLogin();
        return;
      }
      setPermissions(decodePermissions(token));
      setOwner(isOwner());

      let staffBody: StaffPage;
      try {
        staffBody = await api.get<StaffPage>("/staff?limit=50");
      } catch (e) {
        if (e instanceof ApiError && e.status === 403) setForbidden(true);
        else setError(e instanceof ApiError && e.status < 500 ? "Couldn't load staff. Try again." : reasonText(e, ""));
        return;
      }
      setStaff(staffBody.data);
      setNextCursor(staffBody.page.nextCursor);
      // The side reads fail on their own (a flag or an empty list), never the whole page.
      await loadSide(token);
    } finally {
      setLoading(false);
    }
  }, [loadSide]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  async function loadMore() {
    if (!nextCursor) return;
    setLoadingMore(true);
    try {
      const body = await api.get<StaffPage>(`/staff?limit=50&cursor=${encodeURIComponent(nextCursor)}`);
      setStaff((prev) => [...prev, ...body.data]);
      setNextCursor(body.page.nextCursor);
    } catch {
      /* the Load more button stays; pressing it again retries */
    } finally {
      setLoadingMore(false);
    }
  }

  return { ...side, permissions, staff, setStaff, nextCursor, loading, loadingMore, error, setError, forbidden, owner, load, loadMore };
}
