"use client";
// FEF-052: facility switcher for multi-branch owners. Lists the owner's clinics (GET /owners/me/workspaces with the ~5 min pending
// token) and enters the picked one (POST /owners/workspaces/select), exactly as /owner/workspaces does. The new clinic token IS the
// persisted choice: every screen reads it, so a reload re-scopes all of them. Without a valid pending token the old sign-in-again path stays.
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import Link from "next/link";
import { useRouter } from "next/navigation";
import styles from "../clinicNav.module.css";
import Icon from "../components/ui/Icon";
import { publicApi, ApiError } from "../lib/api";
import { storeTokens } from "../lib/apiClient";
import { COOKIE_AUTH } from "../lib/bffShim";
import { ownerPendingTokenValid, regionLabel, signOut } from "../lib/session";
import { rememberFacilities, setFacilityScope, useFacilityScope } from "./facilityScope";

type Clinic = { id: string; name: string; slug: string; region: string; status: string };
type Brand = { id: string; name: string; clinics: Clinic[] };
type TokenPair = { accessToken: string; refreshToken: string; expiresIn: number };
const pending = () => sessionStorage.getItem("nabd_owner_pending_token");

export default function FacilitySwitcher({ name, region, tenantId, branchLocked, onSignInAgain, signingOut }: {
  name: string; region: string; tenantId: string | null; branchLocked: boolean; onSignInAgain: () => void; signingOut: boolean;
}) {
  const tf = useTranslations("shell.facility");
  const [open, setOpen] = useState(false);
  const scope = useFacilityScope();
  const router = useRouter();
  useEffect(() => {
    if (!open) return;
    const k = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("keydown", k);
    return () => document.removeEventListener("keydown", k);
  }, [open]);
  const [brands, setBrands] = useState<Brand[] | null>(null);
  const [canList, setCanList] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function toggle() {
    const next = !open;
    setOpen(next);
    setError(null);
    if (!next) return;
    const ok = ownerPendingTokenValid(); // re-checked on every open: the pending token only lives ~5 minutes
    setCanList(ok);
    if (!ok) return;
    try {
      const body = await publicApi.get<{ brands: Brand[] }>("/owners/me/workspaces", { headers: { Authorization: `Bearer ${pending()}` }, keepSession: true });
      setBrands(body.brands);
      rememberFacilities(body.brands.flatMap((b) => b.clinics.map((c) => ({ id: c.id, name: c.name, brand: b.name }))));
    } catch (e) {
      setError(e instanceof ApiError && e.status === 401 ? "Your clinic list expired. Sign in again to pick a clinic." : "Couldn't load your clinics.");
      setCanList(false);
    }
  }

  async function enter(clinicId: string) {
    setBusy(clinicId);
    setError(null);
    try {
      setFacilityScope("one"); // a picked facility ends the aggregate scope
      if (COOKIE_AUTH) await signOut(); // FEF-009: one cookie session per browser
      const pair = await publicApi.post<TokenPair>("/owners/workspaces/select", { clinicId }, { headers: { Authorization: `Bearer ${pending()}` }, keepSession: true });
      if (!COOKIE_AUTH) await signOut();
      storeTokens(pair);
      window.location.assign(window.location.pathname); // full reload: every screen and cache re-scopes to the new clinic
    } catch {
      setError("Couldn't enter that clinic.");
      setBusy(null);
    }
  }

  const all = (brands ?? []).flatMap((b) => b.clinics.map((c) => ({ ...c, brand: b.name })));
  return (
    <div className={styles.clinicWrap}>
      <button type="button" className={`${styles.clinicInfo} ${styles.clinicButton}`} aria-haspopup="menu" aria-expanded={open} aria-label={scope === "all" ? tf("clinicAll") : tf("clinicNamed", { name })} onClick={() => void toggle()}>
        <div className={styles.clinicIcon}><Icon name="building" size={16} /></div>
        <div className={styles.clinicText}><div className={styles.clinicName}>{scope === "all" ? tf("allFacilities") : <bdi>{name}</bdi>}</div></div>
        <span className={styles.clinicChevron}><Icon name="chevron" size={14} /></span>
      </button>
      {open && (
        <>
          <div className={styles.menuBackdrop} onClick={() => setOpen(false)} />
          <div className={styles.clinicMenu} role="menu" aria-label={tf("menu")}>
            <div className={styles.menuHeader}>{tf("menu")}</div>
            {canList && !brands && !error && <div className={styles.clinicRowSub} role="status">{tf("loading")}</div>}
            {all.length > 1 && (
              <button type="button" role="menuitemradio" aria-checked={scope === "all"} className={styles.clinicSwitch}
                onClick={() => { setFacilityScope(scope === "all" ? "one" : "all"); setOpen(false); router.push("/overview"); }}>
                {tf("allFacilities")}
                <span className={styles.clinicRowSub}>{scope === "all" ? tf("showingAll") : tf("readOnlyAggregate")}</span>
              </button>
            )}
            {all.length === 0 && (
              <div className={styles.clinicCurrent} role="menuitem" aria-current="true">
                <Icon name="building" size={14} />
                <span className={styles.clinicRowText}><span><bdi>{name}</bdi></span>{region && <span className={styles.clinicRowSub}>{regionLabel(region)}</span>}</span>
                <span className={styles.clinicRowTag}>{tf("current")}</span>
              </div>
            )}
            {all.map((c) => c.id === tenantId ? (
              <div key={c.id} className={styles.clinicCurrent} role="menuitem" aria-current="true">
                <Icon name="building" size={14} />
                <span className={styles.clinicRowText}><span><bdi>{c.name}</bdi></span><span className={styles.clinicRowSub}>{tf("brandRegion", { brand: c.brand, region: regionLabel(c.region) })}</span></span>
                <span className={styles.clinicRowTag}>{tf("current")}</span>
              </div>
            ) : (
              <button key={c.id} type="button" role="menuitem" className={styles.clinicSwitch} disabled={busy !== null} onClick={() => void enter(c.id)}>
                <bdi>{c.name}</bdi>
                <span className={styles.clinicRowSub}>{busy === c.id ? tf("entering") : tf("brandRegion", { brand: c.brand, region: regionLabel(c.region) })}</span>
              </button>
            ))}
            {error && <div className={styles.clinicRowSub} role="alert">{error}</div>}
            {branchLocked && (
              <Link href="/setup?tab=subscription" role="menuitem" className={styles.clinicLocked} onClick={() => setOpen(false)}>
                <Icon name="lock" size={14} />
                <span className={styles.clinicRowText}><span>{tf("moreBranches")}</span><span className={styles.clinicRowSub}>{tf("locked")}</span></span>
              </Link>
            )}
            {!canList && (
              <button type="button" role="menuitem" className={styles.clinicSwitch} onClick={onSignInAgain} disabled={signingOut}>
                {signingOut ? tf("signingOut") : tf("switchClinic")}
                <span className={styles.clinicRowSub}>{tf("signsOut")}</span>
              </button>
            )}
          </div>
        </>
      )}
    </div>
  );
}
