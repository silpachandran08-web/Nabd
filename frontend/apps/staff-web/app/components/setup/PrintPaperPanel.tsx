"use client";

import { BUILTIN_PAPER, DOC_TYPES, PAPERS, isPaper, policyKey, type DocType } from "@nabd/ui";
import styles from "../../(clinic)/setup/setup.module.css";

// FEF-046: the clinic's default paper per document type (policy print.default_paper.<type>). No row = the built-in default.
const LABEL: Record<DocType, string> = { token_slip: "Token slip", rx: "Prescription", document: "Clinical documents", invoice: "Tax invoice", receipt: "Receipt" };
const PAPER_LABEL = { a4: "A4", a5: "A5", "80mm": "80 mm roll" } as const;

export default function PrintPaperPanel({ policies, canEdit, onSave }: {
  policies: { policyKey: string; value: string }[]; canEdit: boolean; onSave: (key: string, value: string) => Promise<boolean>;
}) {
  return (
    <div className={styles.card}>
      <h2 className={styles.cardTitle}>Default paper for printing</h2>
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead><tr><th scope="col">Document</th><th scope="col">Paper</th></tr></thead>
          <tbody>
            {DOC_TYPES.map((t) => {
              const stored = policies.find((p) => p.policyKey === policyKey(t))?.value;
              const paper = isPaper(stored) ? stored : BUILTIN_PAPER[t];
              return (
                <tr key={t}>
                  <td><label htmlFor={`paper-${t}`}>{LABEL[t]}</label></td>
                  <td>
                    {canEdit ? (
                      <select id={`paper-${t}`} value={paper} onChange={(e) => void onSave(policyKey(t), e.target.value)}>
                        {PAPERS.map((p) => <option key={p} value={p}>{PAPER_LABEL[p]}</option>)}
                      </select>
                    ) : PAPER_LABEL[paper]}
                    {!isPaper(stored) && <span> (built-in)</span>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
