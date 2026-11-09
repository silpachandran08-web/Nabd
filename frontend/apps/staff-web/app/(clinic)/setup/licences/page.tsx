"use client";

import styles from "../setup.module.css";
import LicenceTable, { type LicenceRow } from "../../../components/setup/LicenceTable";
import { ISSUING_BODIES } from "@nabd/domain";
import { clinicToday } from "../../../lib/clinicTime";
import { getIdentity } from "../../../lib/session";
import { useSetupState } from "../setupStore";
import { SetupTabBody, useSetupTab, type StaffSummary } from "../setupTab";

type Licence = LicenceRow;

export default function LicencesTab() {
  const tab = useSetupTab<{ licences: Licence[]; staff: StaffSummary[] }>({ licences: "/setup/licences", staff: "/setup/staff" }, { licences: [], staff: [] });
  const { licences, staff } = tab.data;
  // The API accepts IN, AE, SA or QA; default to the clinic's own region (fixed at provisioning).
  const licenceRegion = ["IN", "AE", "SA", "QA"].find((c) => c === getIdentity()?.tenantRegion) ?? "IN";
  const [newLicence, setNewLicence] = useSetupState<Partial<Licence>>("newLicence", { region: licenceRegion, licenceType: "clinician" });
  const addLicence = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newLicence.licenceType || !newLicence.number || !newLicence.expiryDate || !newLicence.region) return;
    if (await tab.post("/setup/licences", newLicence)) {
      setNewLicence({ region: licenceRegion, licenceType: "clinician" });
      tab.loadAll();
    }
  };
  return (
    <SetupTabBody tab={tab}>
      <div className={styles.card}>
        <h2 className={styles.cardTitle}>Facility & licence registry</h2>
        <form onSubmit={addLicence}>
          <div className={styles.row}>
            <div className={styles.field}>
              <label className={styles.label}>Type</label>
              <select className={styles.select} value={newLicence.licenceType} onChange={(e) => setNewLicence({ ...newLicence, licenceType: e.target.value })}>
                <option value="clinician">Clinician</option>
                <option value="facility">Facility</option>
                <option value="nurse">Nurse</option>
                <option value="lab">Lab staff</option>
                <option value="pharmacist">Pharmacist</option>
              </select>
            </div>
            {newLicence.licenceType !== "facility" && (
              <div className={styles.field}>
                <label className={styles.label} htmlFor="licence-holder">Holder</label>
                <select id="licence-holder" className={styles.select} value={newLicence.holderId ?? ""} onChange={(e) => setNewLicence({ ...newLicence, holderId: e.target.value || null })}>
                  <option value="">Choose staff member</option>
                  {staff.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
                </select>
              </div>
            )}
            <div className={styles.field}>
              <label className={styles.label}>Number</label>
              <input className={styles.input} value={newLicence.number ?? ""} onChange={(e) => setNewLicence({ ...newLicence, number: e.target.value })} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Issuing body</label>
              <input className={styles.input} list="licence-issuing-bodies" value={newLicence.issuingBody ?? ""} onChange={(e) => setNewLicence({ ...newLicence, issuingBody: e.target.value })} />
              <datalist id="licence-issuing-bodies">{(ISSUING_BODIES[newLicence.region ?? licenceRegion] ?? []).map((b) => <option key={b} value={b} />)}</datalist>
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Expiry</label>
              <input className={styles.input} type="date" value={newLicence.expiryDate ?? ""} onChange={(e) => setNewLicence({ ...newLicence, expiryDate: e.target.value })} />
            </div>
          </div>
          <div className={styles.actions}>
            <button className={styles.btnPrimary} type="submit">Add licence</button>
          </div>
        </form>
        <LicenceTable licences={licences} today={clinicToday()} onChanged={tab.loadAll} />
      </div>
    </SetupTabBody>
  );
}
