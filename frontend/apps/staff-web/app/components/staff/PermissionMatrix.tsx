import Icon from "../ui/Icon";
import styles from "./PermissionMatrix.module.css";

export type MatrixGrant = {
  module: string; view: boolean; create: boolean; edit: boolean; delete: boolean;
  approve: boolean; refundDiscount: boolean; export: boolean;
};

// Wireframe order (View, Create, Edit, Approve, Export, Refund, Configure). The API has no
// "configure" grant, so that column is always "not applicable"; Delete is kept because the API
// does grant it and hiding a granted action would understate the role's access. Delete sits
// before the always-N/A Configure so a narrow layout can never push a real grant out of view.
const COLUMNS: { key: keyof Omit<MatrixGrant, "module"> | "configure"; label: string }[] = [
  { key: "view", label: "View" },
  { key: "create", label: "Create" },
  { key: "edit", label: "Edit" },
  { key: "approve", label: "Approve" },
  { key: "export", label: "Export" },
  { key: "refundDiscount", label: "Refund" },
  { key: "delete", label: "Delete" },
  { key: "configure", label: "Configure" },
];

// The wireframe's 10 modules (PERM_MODULES), in its order. Every other module the API grants
// (specialty, nursing, departments) is folded into a second group below so no access is hidden.
const CORE_ORDER = ["patients", "queue", "clinical", "billing", "packages", "pharmacy", "reports", "staff", "setup"];
// "Compliance & Audit" has no grant of its own in the clinic API: its screen (ClinicNav, /setup?tab=licences)
// is opened with Clinic Setup's view grant, so the row mirrors setup.view and nothing else applies.
const COMPLIANCE_ROW = { key: "compliance_audit", label: "Compliance & Audit", via: "setup" };

type CellState = "yes" | "no" | "na";

function Cell({ state }: { state: CellState }) {
  if (state === "na") {
    return <span className={`${styles.cell} ${styles.na}`} title="Not applicable">—<span className={styles.sr}>Not applicable</span></span>;
  }
  const on = state === "yes";
  return (
    <span className={`${styles.cell} ${on ? styles.yes : styles.no}`}>
      <Icon name={on ? "check" : "x"} size={12} />
      <span className={styles.sr}>{on ? "Granted" : "Not granted"}</span>
    </span>
  );
}

// Read-only. Every cell carries an icon and a text label (not colour alone).
export default function PermissionMatrix({
  roleName, grants, modules,
}: {
  roleName: string;
  grants: MatrixGrant[];
  modules: { key: string; label: string }[];
}) {
  const core = CORE_ORDER.map((k) => modules.find((m) => m.key === k)).filter((m): m is { key: string; label: string } => !!m);
  const extra = modules.filter((m) => !CORE_ORDER.includes(m.key));
  const grantOf = (key: string) => grants.find((x) => x.module === key);

  const row = (key: string, label: string) => {
    const g = grantOf(key);
    return (
      <tr key={key}>
        <th scope="row">{label}</th>
        {COLUMNS.map((c) => (
          <td key={c.key}><Cell state={c.key === "configure" ? "na" : g?.[c.key] ? "yes" : "no"} /></td>
        ))}
      </tr>
    );
  };

  const setupView = !!grantOf(COMPLIANCE_ROW.via)?.view;
  const complianceRow = (
    <tr key={COMPLIANCE_ROW.key}>
      <th scope="row">
        {COMPLIANCE_ROW.label}
        <span className={styles.via}>via Clinic Setup</span>
      </th>
      {COLUMNS.map((c) => (
        <td key={c.key}><Cell state={c.key === "view" ? (setupView ? "yes" : "no") : "na"} /></td>
      ))}
    </tr>
  );

  return (
    <section className={styles.wrap} aria-label={`Permissions: ${roleName}`}>
      <div className={styles.head}>
        <h3 className={styles.title}>Permissions: {roleName}</h3>
        <ul className={styles.legend}>
          <li className={`${styles.chip} ${styles.yes}`}><Icon name="check" size={12} /> Granted</li>
          <li className={`${styles.chip} ${styles.no}`}><Icon name="x" size={12} /> Not granted</li>
          <li className={`${styles.chip} ${styles.na}`}>— Not applicable</li>
        </ul>
      </div>
      <p className={styles.sub}>
        Permissions are grouped into {core.length + 1} modules × {COLUMNS.length} actions
        {extra.length > 0 ? `, plus ${extra.length} specialty and department ${extra.length === 1 ? "module" : "modules"}` : ""}.
        Financial, clinical and personal fields stay protected at field level even inside granted modules.
      </p>
      {/* axe scrollable-region-focusable: the table scrolls sideways on narrow screens, so keyboard users can focus it */}
      <div className={styles.scroll} tabIndex={0} role="region" aria-label={`Permission table: ${roleName}`}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th scope="col">Module</th>
              {COLUMNS.map((c) => <th scope="col" key={c.key}>{c.label}</th>)}
            </tr>
          </thead>
          <tbody>
            {core.map((m) => row(m.key, m.label))}
            {complianceRow}
          </tbody>
          {extra.length > 0 && (
            <tbody>
              <tr><th scope="rowgroup" colSpan={COLUMNS.length + 1} className={styles.group}>Specialty &amp; department modules</th></tr>
              {extra.map((m) => row(m.key, m.label))}
            </tbody>
          )}
        </table>
      </div>
    </section>
  );
}
