"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import Dialog from "../../../components/ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/StateViews";
import { errorText } from "../../../lib/api";
import { getter, key } from "../../../lib/query";
import { REGIONS, addPhotoStep, comparePair, togglePick, urlFresh, type DermPhoto, type Side, type Timeline } from "../../../lib/dermPhotos";
import { CaptureDialog, CompareDialog, ConsentDialog, PhotoImg, WithdrawDialog } from "./DermPhotoDialogs";
import s from "./dermPhotos.module.css";

// CLN-047: the dermatology photo timeline in the patient drawer (prototype doctor.specialty). Photo consent first, then dated
// photos per body site and visit with a consent badge; compare two dates side by side or with an overlay slider. Writes are
// absent without specialty_derm:edit; the server repeats every gate.

export default function DermPhotosTab({ patientId, visitId, canEdit }: { patientId: string; visitId: string | null; canEdit: boolean }) {
  const t = useTranslations("shell.dermPhotos");
  const locale = useLocale();
  const qc = useQueryClient();
  const qKey = key("derm-photos", { id: patientId });
  // signed URLs live 5 minutes: never serve a cached timeline older than 4
  const q = useQuery({ queryKey: qKey, queryFn: getter<Timeline>(`/specialty/derm/patients/${patientId}/photos`), staleTime: 60_000, refetchInterval: 240_000 });
  const [dialog, setDialog] = useState<null | "consent" | "capture" | "withdraw">(null);
  const [picked, setPicked] = useState<string[]>([]);
  const [comparing, setComparing] = useState<[DermPhoto, DermPhoto] | null>(null);
  const reload = () => qc.invalidateQueries({ queryKey: qKey });
  const date = (iso: string) => new Date(iso).toLocaleDateString(locale, { day: "2-digit", month: "short", year: "numeric" });
  const siteLabel = (region: string, side: Side) => `${REGIONS.includes(region as (typeof REGIONS)[number]) ? t(`region_${region}`) : region} · ${t(`side_${side}`)}`;

  if (q.isPending) return <Skeleton rows={4} label={t("loading")} />;
  if (q.isError) return <ErrorState message={errorText(q.error, t("loadFailed"))} onRetry={() => void q.refetch()} retryLabel={t("retry")} />;
  const tl = q.data;
  const step = addPhotoStep(tl, visitId);

  async function openCompare(photos: DermPhoto[]) {
    let pair = comparePair(photos, picked);
    if (pair && !pair.every((p) => urlFresh(p))) {
      const fresh = (await q.refetch()).data?.sites.flatMap((x) => x.photos) ?? [];
      pair = comparePair(fresh, picked);
    }
    setComparing(pair);
  }

  return (
    <section className={s.tab} aria-label={t("tab")}>
      <div className={s.head}>
        {tl.consent
          ? <span className={s.badge}>{t("consentActive", { scope: t(`scope_${tl.consent.scope}`) })}</span>
          : <span className={s.muted}>{t("consentNone")}</span>}
        <div className={s.headActions}>
          {canEdit && <button type="button" className={s.primary} onClick={() => setDialog(step === "consent" ? "consent" : "capture")}>{t("addPhoto")}</button>}
          {canEdit && tl.consent && <button type="button" className={s.secondary} onClick={() => setDialog("withdraw")}>{t("withdraw")}</button>}
        </div>
      </div>
      {!tl.consent && <p className={s.muted}>{t("consentNoneHint")}</p>}
      {tl.hiddenCount > 0 && <p className={s.notice} role="status">{t("hidden", { count: tl.hiddenCount })}</p>}

      {tl.sites.length === 0 ? (
        <EmptyState icon="inbox" title={t("empty")} hint={t("emptyHint")} />
      ) : tl.sites.map((g) => {
        const label = siteLabel(g.site.region, g.site.side);
        const pairHere = comparePair(g.photos, picked);
        return (
          <div key={`${g.site.region}|${g.site.side}|${g.site.mapPointId ?? ""}`} className={s.site}>
            <div className={s.siteHead}>
              <h3 className={s.siteTitle}>{label}</h3>
              {pairHere
                ? <button type="button" className={s.secondary} onClick={() => void openCompare(g.photos)}>{t("compare")}</button>
                : g.photos.length > 1 && <span className={s.muted}>{t("comparePick")}</span>}
            </div>
            <ul className={s.strip}>
              {g.photos.map((p) => (
                <li key={p.id} className={s.card}>
                  <PhotoImg photo={p} alt={`${label}, ${date(p.takenAt)}`} className={s.thumb} />
                  <span className={s.meta}>{t("takenOn", { date: date(p.takenAt) })}</span>
                  {p.visitDate && <span className={s.meta}>{t("visitOn", { date: date(p.visitDate) })}</span>}
                  <span className={s.badge}>{t(`badge_${p.consentBadge}`)}</span>
                  {g.photos.length > 1 && (
                    <label className={s.pick}>
                      <input type="checkbox" checked={picked.includes(p.id)} onChange={() => setPicked((x) => togglePick(x, p.id))} />
                      {t("pick")}
                    </label>
                  )}
                </li>
              ))}
            </ul>
          </div>
        );
      })}

      {dialog === "consent" && canEdit && (
        <ConsentDialog patientId={patientId} onClose={() => setDialog(null)}
          onDone={async () => { await reload(); setDialog(visitId ? "capture" : null); }} />
      )}
      {dialog === "capture" && canEdit && (
        step === "visit"
          ? <Dialog title={t("captureTitle")} onClose={() => setDialog(null)}><p className={s.muted}>{t("needVisit")}</p></Dialog>
          : step === "camera" && visitId
            ? <CaptureDialog patientId={patientId} visitId={visitId} onClose={() => setDialog(null)} onDone={async () => { await reload(); setDialog(null); }} />
            : null
      )}
      {dialog === "withdraw" && canEdit && (
        <WithdrawDialog patientId={patientId} onClose={() => setDialog(null)} onDone={async () => { setPicked([]); await reload(); setDialog(null); }} />
      )}
      {comparing && <CompareDialog pair={comparing} label={siteLabel(comparing[0].site.region, comparing[0].site.side)} date={date} onClose={() => setComparing(null)} />}
    </section>
  );
}
