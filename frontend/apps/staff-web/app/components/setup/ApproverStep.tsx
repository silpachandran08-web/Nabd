"use client";

import { useEffect, useState } from "react";
import styles from "../../(clinic)/setup/setup.module.css";
import ph from "./pharmacy.module.css";
import { api, errorText } from "../../lib/api";
import type { Full } from "../../lib/gen";
import type { AdjustmentApprover } from "@nabd/api-client/generated";

export type Approval = { approverStaffId: string; approverPin: string };
type Approver = Full<AdjustmentApprover>;

/** PHL-010 maker-checker: a second staff member with approval rights picks their name and types their own PIN on this
 * screen (a step-up of that person, not a shared login). The server checks the right, the PIN and that it is not the maker. */
export default function ApproverStep({ idPrefix, value, onChange }: { idPrefix: string; value: Approval; onChange: (a: Approval) => void }) {
  const [list, setList] = useState<Approver[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    api.get<Approver[]>("/pharmacy/adjustment-approvers")
      .then((l) => { if (live) setList(l); })
      .catch((e) => { if (live) setError(errorText(e, "Couldn't load the approvers.")); });
    return () => { live = false; };
  }, []);
  return (
    <fieldset className={ph.warn} style={{ border: 0 }}>
      <legend><strong>Second sign-off (controlled drug)</strong></legend>
      <p className={ph.sub}>Another staff member with approval rights confirms with their own PIN. Both names are kept on the register entry.</p>
      {error ? <div role="alert">{error}</div> : list == null ? <div className={ph.sub}>Loading approvers…</div> : list.length === 0 ? (
        <div role="alert">No other staff member can approve. Ask the owner to give a pharmacist approval rights on Pharmacy.</div>
      ) : (
        <div className={ph.formRow}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor={`${idPrefix}-approver`}>Approver</label>
            <select id={`${idPrefix}-approver`} className={styles.select} value={value.approverStaffId}
              onChange={(e) => onChange({ ...value, approverStaffId: e.target.value })}>
              <option value="">Choose…</option>
              {list.map((a) => <option key={a.staffId} value={a.staffId}>{a.name}</option>)}
            </select>
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor={`${idPrefix}-pin`}>Approver PIN</label>
            <input id={`${idPrefix}-pin`} className={styles.input} type="password" inputMode="numeric" autoComplete="off" maxLength={8}
              value={value.approverPin} onChange={(e) => onChange({ ...value, approverPin: e.target.value })} />
          </div>
        </div>
      )}
    </fieldset>
  );
}
