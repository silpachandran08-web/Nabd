"use client";

// FIN-017: the wizard's Price master step. The owner picks a specialty, edits the pre-filled starter list and sees the receptionist's
// charge buttons update as they type (derived from local state, no API call per edit); Save adds the missing heads (add-only).
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { formatMoney } from "@nabd/domain";
import setupStyles from "../../(clinic)/setup/setup.module.css";
import styles from "./priceTemplate.module.css";
import ChargeButton from "../ui/ChargeButton";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { api, intentKeys, reasonText } from "../../lib/api";
import { can } from "../../lib/permissions";
import { isOwner } from "../../lib/session";
import { SPECIALTIES, amountError, bulkBody, existingCodes, gridError, move, nameError, preview, rowsOf, taxBadge,
  type ExistingCharge, type PriceTemplate, type Row } from "../../lib/priceTemplate";

export default function PriceTemplateStep() {
  const [existing, setExisting] = useState<ExistingCharge[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [specialty, setSpecialty] = useState<string | null>(null);
  const [template, setTemplate] = useState<PriceTemplate | null>(null);
  const [rows, setRows] = useState<Row[]>([]);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const keys = useRef(intentKeys());
  const canSave = isOwner() && can("setup:edit");

  const loadExisting = useCallback(async () => {
    setLoadError(null);
    try { setExisting(await api.get<ExistingCharge[]>("/setup/charges")); }
    catch (e) { setLoadError(reasonText(e, "Could not load your price list.")); }
  }, []);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- state is set after the awaited fetch
  useEffect(() => { void loadExisting(); }, [loadExisting]);

  const latestPick = useRef<string | null>(null);
  const pick = async (sp: string) => {
    latestPick.current = sp;
    setSpecialty(sp); setTemplate(null); setRows([]); setMsg(null); setLoadError(null);
    try {
      const t = await api.get<PriceTemplate>(`/setup/price-templates?specialty=${encodeURIComponent(sp)}`);
      if (latestPick.current !== sp) return; // a newer pick won: drop this stale answer (S17-BUG-2)
      setTemplate(t); setRows(rowsOf(t));
    } catch (e) { if (latestPick.current === sp) setLoadError(reasonText(e, "Could not load the starter list.")); }
  };

  const have = useMemo(() => existingCodes(existing ?? []), [existing]);
  const shown = useMemo(() => (template ? preview(rows, existing ?? [], template.jurisdiction) : null), [rows, existing, template]);
  const problem = template ? gridError(rows, existing ?? []) : null;
  const edit = (i: number, patch: Partial<Row>) => setRows((prev) => prev.map((r, k) => (k === i ? { ...r, ...patch } : r)));

  async function save() {
    if (!template || problem) return;
    const body = bulkBody(template, rows);
    setBusy(true); setMsg(null);
    try {
      const r = await api.post<{ created: string[]; skipped: string[] }>("/setup/charges/bulk", body, { idempotencyKey: keys.current.get("bulk", body) });
      keys.current.done("bulk");
      setMsg({ ok: true, text: `${r.created.length} added to your price list${r.skipped.length ? `, ${r.skipped.length} already there and kept as they were` : ""}.` });
      await loadExisting();
    } catch (e) { setMsg({ ok: false, text: reasonText(e, "Could not save the price list.") }); }
    setBusy(false);
  }

  if (existing === null && !loadError) return <Skeleton rows={4} label="Loading price list" />;

  return (
    <div>
      <p className={setupStyles.hint}>Pick your specialty for a starter price list. Amounts are suggestions: change anything before you save. Heads you already have are kept as they are.</p>
      <div className={styles.specialties} role="group" aria-label="Specialty">
        {SPECIALTIES.map(([k, label]) => (
          <button key={k} type="button" className={styles.specialty} aria-pressed={specialty === k} onClick={() => void pick(k)}>{label}</button>
        ))}
      </div>

      {loadError && <ErrorState message={loadError} onRetry={() => void (existing === null ? loadExisting() : specialty && pick(specialty))} />}
      {!loadError && !specialty && <EmptyState title="No specialty picked yet" hint="Pick one above to see its starter list and your billing screen." />}
      {!loadError && specialty && !template && <Skeleton rows={4} label="Loading starter list" />}

      {template && shown && (
        <>
          {!template.reviewed && <p className={setupStyles.hint} role="note">Starter tax categories and rates are pending review by our billing team and a chartered accountant. Check them with your accountant.</p>}
          <div className={styles.layout}>
            <div className={styles.grid} aria-label="Starter price list">
              {rows.map((r, i) => {
                const kept = have.has(r.code.trim().toUpperCase());
                const err = r.include && !kept ? nameError(r.name) ?? amountError(r.amount) : null;
                return (
                  <div key={r.code} className={styles.row}>
                    <input type="checkbox" className={styles.check} checked={r.include && !kept} disabled={kept} aria-label={`Include ${r.name}`}
                      onChange={(e) => edit(i, { include: e.target.checked })} />
                    <input className={styles.cellInput} value={r.name} aria-label={`Name of ${r.code}`} readOnly={kept} onChange={(e) => edit(i, { name: e.target.value })} />
                    <input className={styles.cellInput} value={r.amount} inputMode="decimal" aria-label={`Amount of ${r.code}`} aria-invalid={!!err} readOnly={kept}
                      onChange={(e) => edit(i, { amount: e.target.value })} />
                    <div className={styles.rowMeta}>
                      <span>{r.category}</span>
                      <span className={styles.badge}>{taxBadge(r.taxCategory, r.taxRatePercent, template.jurisdiction) || "No tax"}</span>
                      {r.sacHsn && <span>SAC {r.sacHsn}</span>}
                      {kept && <span>Already in your price list, kept as is</span>}
                      {err && <span role="alert" className={styles.error}>{err}</span>}
                      <span style={{ marginInlineStart: "auto", display: "flex", gap: 4 }}>
                        <button type="button" className={styles.moveBtn} aria-label={`Move ${r.name} up`} onClick={() => setRows((p) => move(p, i, -1))} disabled={i === 0}>↑</button>
                        <button type="button" className={styles.moveBtn} aria-label={`Move ${r.name} down`} onClick={() => setRows((p) => move(p, i, 1))} disabled={i === rows.length - 1}>↓</button>
                      </span>
                    </div>
                  </div>
                );
              })}
            </div>

            <aside className={styles.preview} aria-label="Billing screen preview" aria-live="polite">
              <h3 className={styles.previewTitle}>Your billing screen</h3>
              <div className={styles.tabs}>{shown.categories.map((c) => <span key={c} className={styles.tab}>{c}</span>)}</div>
              {shown.buttons.length === 0
                ? <p className={setupStyles.hint}>No charges yet.</p>
                : shown.buttons.map((b) => (
                  <ChargeButton key={b.code} label={<>{b.name} {b.badge && <span className={styles.badge}>{b.badge}</span>}</>}
                    price={b.amount == null ? "—" : formatMoney(b.amount, template.currency)} />
                ))}
            </aside>
          </div>

          {canSave
            ? <div className={setupStyles.actions}>
                {problem && <span className={styles.error}>{problem}</span>}
                <button type="button" className={setupStyles.btnPrimary} style={{ minBlockSize: 44 }} onClick={() => void save()} disabled={busy || !!problem}>Save price list</button>
              </div>
            : <p className={setupStyles.hint}>Only the clinic owner can save a starter price list.</p>}
          {msg && <div role={msg.ok ? "status" : "alert"} className={msg.ok ? setupStyles.notice : styles.error}>{msg.text}</div>}
        </>
      )}
    </div>
  );
}
