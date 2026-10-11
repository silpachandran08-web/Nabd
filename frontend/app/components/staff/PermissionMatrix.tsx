import Icon from "../ui/Icon";
import styles from "./PermissionMatrix.module.css";

export type MatrixGrant = {
  module: string; view: boolean; create: boolean; edit: boolean; delete: boolean;
  approve: boolean; refundDiscount: boolean; export: boolean;
};

// Wireframe order (View, Create, Edit, Approve, Export, Refund, Configure). The API has no
// "configure" grant, so that column is always "not applicable"; Delete is kept because the API
// does grant it and hiding a granted action would understate the role's access.
const COLUMNS: { key: keyof Omit<MatrixGrant, "module"> | "configure"; label: string }[] = [
  { key: "view", label: "View" },
  { key: "create", label: "Create" },
  { key: "edit", label: "Edit" },
  { key: "approve", label: "Approve" },
  { key: "export", label: "Export" },
  { key: "refundDiscount", label: "Refund" },
  { key: "configure", label: "Configure" },
  { key: "delete", label: "Delete" },
];

// Read-only. Every cell carries an icon and a text label (not colour alone).
export default function PermissionMatrix({
  roleName, grants, modules,
}: {
  roleName: string;
  grants: MatrixGrant[];
  modules: { key: string; label: string }[];
}) {
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
      <p className={styles.sub}>Financial, clinical and personal fields stay protected at field level even inside granted modules.</p>
      <div className={styles.scroll}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th scope="col">Module</th>
              {COLUMNS.map((c) => <th scope="col" key={c.key}>{c.label}</th>)}
            </tr>
          </thead>
          <tbody>
            {modules.map((m) => {
              const g = grants.find((x) => x.module === m.key);
              return (
                <tr key={m.key}>
                  <th scope="row">{m.label}</th>
                  {COLUMNS.map((c) => {
                    if (c.key === "configure") {
                      return <td key={c.key}><span className={`${styles.cell} ${styles.na}`} title="Not applicable">—<span className={styles.sr}>Not applicable</span></span></td>;
                    }
                    const on = !!g?.[c.key];
                    return (
                      <td key={c.key}>
                        <span className={`${styles.cell} ${on ? styles.yes : styles.no}`}>
                          <Icon name={on ? "check" : "x"} size={12} />
                          <span className={styles.sr}>{on ? "Granted" : "Not granted"}</span>
                        </span>
                      </td>
                    );
                  })}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}
