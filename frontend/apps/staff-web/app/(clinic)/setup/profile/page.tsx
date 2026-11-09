"use client";

import { useEffect, useState } from "react";
import styles from "../setup.module.css";
import TimezoneSelect from "../TimezoneSelect";
import Icon from "../../../components/ui/Icon";
import { can } from "../../../lib/permissions";
import { regionLabel } from "../../../lib/session";
import { api, ApiError, reasonText } from "../../../lib/api";
import { SetupTabBody, intents, useSetupTab } from "../setupTab";
import { useSetupState } from "../setupStore";

type Profile = { id: string; name: string; region: string; timezone: string; taxId: string | null; taxIdType: string | null; whatsappNumber: string | null; specialties: string[]; status: string; setupCompletedAt: string | null };

export default function ProfileTab() {
  const tab = useSetupTab<{ profile: Profile | null }>({ profile: "/setup/profile" }, { profile: null });
  const profile = tab.data.profile;
  const setProfile = (p: Profile) => tab.setData({ profile: p });
  const [notice, setNotice] = useState<string | null>(null);
  const [specialtyDraft, setSpecialtyDraft] = useSetupState("specialtyDraft", "");
  // Profile edits need setup:edit (PATCH /setup/profile); without it the profile is shown read-only.
  const [canEditProfile, setCanEditProfile] = useState(false);
  const [profileSaving, setProfileSaving] = useState(false);
  const [profileError, setProfileError] = useSetupState<string | null>("profileError", null);
  useEffect(() => {
    void Promise.resolve().then(() => setCanEditProfile(can("setup:edit")));
  }, []);

  // A failed save stays inline (unlike the shared patch(), which swaps the whole tab for an
  // error view) so the owner's edits are not lost.
  const saveProfile = async (e?: React.FormEvent<HTMLFormElement>) => {
    e?.preventDefault();
    if (!profile || profileSaving) return;
    setNotice(null);
    setProfileError(null);
    const body = {
      name: profile.name,
      timezone: profile.timezone,
      taxIdType: profile.taxIdType,
      taxId: profile.taxId,
      whatsappNumber: profile.whatsappNumber,
      specialties: profile.specialties,
    };
    setProfileSaving(true);
    try {
      setProfile(await api.patch<Profile>("/setup/profile", body, { idempotencyKey: intents.get("profile", body) }));
      intents.done("profile");
      setNotice("Profile saved.");
    } catch (e) {
      setProfileError(e instanceof ApiError && e.status === 403 ? "Your role can't change the clinic profile." : e instanceof ApiError && e.status < 500 ? e.detail || "Couldn't save the profile. Try again." : `${reasonText(e, "")} Your changes are still here.`);
    } finally {
      setProfileSaving(false);
    }
  };

  const addSpecialty = () => {
    const v = specialtyDraft.trim();
    setSpecialtyDraft("");
    if (!profile || !v || profile.specialties.some((x) => x.toLowerCase() === v.toLowerCase())) return;
    setProfile({ ...profile, specialties: [...profile.specialties, v] });
  };

  return (
    <SetupTabBody tab={tab}>
      <div className={styles.card}>
        <h2 className={styles.cardTitle}>Clinic profile</h2>
        {profile && (
          <form onSubmit={saveProfile}>
            {notice && <div className={styles.notice} role="status"><Icon name="check" size={14} /> {notice}</div>}
            {profileError && (
              <div className={styles.errorState} role="alert">
                {profileError}{" "}
                <button type="button" className={styles.wizardBtn} disabled={profileSaving} onClick={() => void saveProfile()}>Try again</button>
              </div>
            )}
            {!canEditProfile && <p className={styles.regionNote}>View only: your role can&apos;t change the clinic profile.</p>}
            <fieldset className={styles.plainFieldset} disabled={!canEditProfile || profileSaving}>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="clinic-name">Clinic name</label>
              <input id="clinic-name" className={styles.input} value={profile.name} onChange={(e) => setProfile({ ...profile, name: e.target.value })} />
            </div>
            <div className={styles.regionLock} role="group" aria-label="Region">
              <Icon name="lock" size={14} />
              <span className={styles.regionNote}>Region is locked irreversibly since provisioning — SYS-14 / NFR-04.</span>
              <strong>{regionLabel(profile.region)}{profile.region && regionLabel(profile.region) !== profile.region ? ` (${profile.region})` : ""}</strong>
            </div>
            <div className={styles.row}>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="clinic-timezone">Timezone</label>
                <TimezoneSelect id="clinic-timezone" className={styles.select} value={profile.timezone} onChange={(timezone) => setProfile({ ...profile, timezone })} />
              </div>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="clinic-whatsapp">WhatsApp number</label>
                <input id="clinic-whatsapp" type="tel" className={styles.input} value={profile.whatsappNumber ?? ""} onChange={(e) => setProfile({ ...profile, whatsappNumber: e.target.value })} />
              </div>
            </div>
            <div className={styles.row}>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="clinic-tax-type">Tax ID type</label>
                <select id="clinic-tax-type" className={styles.select} value={profile.taxIdType ?? ""} onChange={(e) => setProfile({ ...profile, taxIdType: e.target.value || null })}>
                  <option value="">—</option>
                  <option value="GSTIN">GSTIN (India)</option>
                  <option value="VAT">VAT (KSA)</option>
                </select>
              </div>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="clinic-tax-id">Tax ID</label>
                <input id="clinic-tax-id" className={styles.input} value={profile.taxId ?? ""} onChange={(e) => setProfile({ ...profile, taxId: e.target.value })} />
              </div>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor={canEditProfile ? "clinic-specialty" : undefined}>Specialties</label>
              <div className={styles.chips}>
                {profile.specialties.length === 0 && <span className={styles.regionNote}>No specialties added yet.</span>}
                {profile.specialties.map((sp) => (
                  <span key={sp} className={styles.chip}>
                    {sp}
                    {canEditProfile && (
                      <button type="button" className={styles.chipX} aria-label={`Remove ${sp}`} onClick={() => setProfile({ ...profile, specialties: profile.specialties.filter((x) => x !== sp) })}>×</button>
                    )}
                  </span>
                ))}
              </div>
              {canEditProfile && <div className={styles.chipAdd}>
                <input
                  id="clinic-specialty"
                  className={styles.input}
                  value={specialtyDraft}
                  placeholder="Type a specialty and press Enter"
                  onChange={(e) => setSpecialtyDraft(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addSpecialty(); } }}
                />
                <button type="button" className={styles.smallBtn} onClick={addSpecialty}>Add</button>
              </div>}
            </div>
            </fieldset>
            {canEditProfile && (
              <div className={styles.actions}>
                <button className={styles.btnPrimary} type="submit" disabled={profileSaving}>{profileSaving ? "Saving…" : "Save profile"}</button>
              </div>
            )}
          </form>
        )}
      </div>
    </SetupTabBody>
  );
}
