"use client";

// PHL-045: three-step sample collection screen. Step 1: identity confirmation (name + method).
// Step 2: fasting check. Step 3: containers in order of draw (from catalogue). On submit, calls the collection API.
import { useState } from "react";
import { useTranslations } from "next-intl";
import { api, errorText } from "../../lib/api";
import styles from "../../(clinic)/setup/setup.module.css";
import type { CollectionRequest, CollectionResponse } from "../../lib/api";

interface LabCollectionProps {
  orderId: string;
  onComplete?: (resp: CollectionResponse) => void;
  onError?: (message: string) => void;
}

type Step = 1 | 2 | 3;

export default function LabCollection({ orderId, onComplete, onError }: LabCollectionProps) {
  const t = useTranslations("shell.lab");
  const [step, setStep] = useState<Step>(1);
  const [method, setMethod] = useState("name_dob");
  const [fastingHours, setFastingHours] = useState<number | null>(null);
  const [containers, setContainers] = useState<{ container: string; testItemIds: string[] }[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const handleStep1Next = () => {
    if (!method) {
      setError(t("collection.identityRequired"));
      return;
    }
    setError(null);
    setStep(2);
  };

  const handleStep2Next = () => {
    setError(null);
    setStep(3);
  };

  const handleSubmit = async () => {
    if (!containers.length) {
      setError(t("collection.containersRequired"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const req: CollectionRequest = {
        identityCheck: { method },
        fastingHours: fastingHours ?? undefined,
        containers,
        collectedAt: new Date().toISOString(),
      };
      const resp = await api.post<CollectionResponse>(`/lab/orders/${orderId}/collections`, req);
      onComplete?.(resp);
    } catch (e) {
      const msg = errorText(e, t("collection.failed"));
      setError(msg);
      onError?.(msg);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={styles.card}>
      <h2>{t("collection.title")}</h2>
      {error && <div className={styles.error} role="alert">{error}</div>}

      {/* Step 1: Identity */}
      {step === 1 && (
        <fieldset>
          <legend>{t("collection.step1")}</legend>
          <label>
            <input
              type="radio"
              name="method"
              value="name_dob"
              checked={method === "name_dob"}
              onChange={(e) => setMethod(e.target.value)}
            />
            {t("collection.method.name_dob")}
          </label>
          <label>
            <input
              type="radio"
              name="method"
              value="name_phone"
              checked={method === "name_phone"}
              onChange={(e) => setMethod(e.target.value)}
            />
            {t("collection.method.name_phone")}
          </label>
          <button type="button" className={styles.btnPrimary} onClick={handleStep1Next}>
            {t("next")}
          </button>
        </fieldset>
      )}

      {/* Step 2: Fasting */}
      {step === 2 && (
        <fieldset>
          <legend>{t("collection.step2")}</legend>
          <label>
            {t("collection.fastingHours")}
            <input
              type="number"
              min="0"
              max="24"
              value={fastingHours ?? ""}
              onChange={(e) => setFastingHours(e.target.value ? parseInt(e.target.value) : null)}
            />
          </label>
          <div>
            <button type="button" className={styles.btn} onClick={() => setStep(1)}>
              {t("back")}
            </button>
            <button type="button" className={styles.btnPrimary} onClick={handleStep2Next}>
              {t("next")}
            </button>
          </div>
        </fieldset>
      )}

      {/* Step 3: Containers */}
      {step === 3 && (
        <fieldset>
          <legend>{t("collection.step3")}</legend>
          <p>{t("collection.containersHint")}</p>
          <div>
            <button type="button" className={styles.btn} onClick={() => setStep(2)}>
              {t("back")}
            </button>
            <button type="button" className={styles.btnPrimary} disabled={busy} onClick={handleSubmit}>
              {busy ? t("collecting") : t("collect")}
            </button>
          </div>
        </fieldset>
      )}
    </div>
  );
}
