"use client";

// FEF-032: the sell-package wizard (wireframe pksell). It mounts each time Sell package opens, so it always starts at
// step 1; the Idempotency-Key store comes from the page, so re-sending the same sale after an error reuses its key.
import { useState, type RefObject } from "react";
import styles from "./packages.module.css";
import StatusPill from "../../components/ui/StatusPill";
import { PAYMENT_METHODS, paymentMethodLabel, formatMoney } from "@nabd/domain";
import { can } from "../../lib/permissions";
import { getIdentity } from "../../lib/session";
import { api, ApiError, type intentKeys } from "../../lib/api";
import { ErrorSummary, useFormErrors } from "../../lib/formErrors";
import { usePatientSearch } from "../../lib/usePatientSearch";
import { BLOCKING_STATUSES, SELL_STEPS, money, type InstanceT, type PackageT, type SoldInvoice, type StaffOption } from "./packagesModel";

export default function SellWizard({ packages, instances, staff, sellKeys, loadAll, onCancel, onOpenInstance }: {
  packages: PackageT[]; instances: InstanceT[]; staff: StaffOption[]; sellKeys: RefObject<ReturnType<typeof intentKeys>>;
  loadAll: () => Promise<unknown>; onCancel: () => void; onOpenInstance: (id: string) => void;
}) {
  const [sellQuery, setSellQuery] = useState("");
  const [sellPatient, setSellPatient] = useState<{ id: string; name: string; phone: string; mrn: string } | null>(null);
  const { results: sellResults } = usePatientSearch(sellPatient ? "" : sellQuery, 10);
  const [sellPackageId, setSellPackageId] = useState("");
  const [sellMethod, setSellMethod] = useState("");
  const [sellStep, setSellStep] = useState(0);
  const [sold, setSold] = useState<InstanceT | null>(null);
  const [soldInvoice, setSoldInvoice] = useState<SoldInvoice | null>(null);
  const sfe = useFormErrors("pks", { patientId: "Patient", packageId: "Package", paymentMethod: "Payment method" });
  const [selling, setSelling] = useState(false);

  function onSellQueryChange(value: string) {
    setSellQuery(value);
    setSellPatient(null);
  }

  const sellPackage = packages.find((p) => p.id === sellPackageId) ?? null;
  const onSale = packages.filter((p) => p.status === "on_sale");

  async function submitSale() {
    if (selling) return;
    sfe.clear();
    if (!sellPatient || !sellPackageId) {
      sfe.set("Pick a patient and a package.");
      return;
    }
    if (!sellMethod) {
      sfe.set("Choose a payment method.");
      return;
    }
    setSelling(true);
    const body = { patientId: sellPatient.id, packageId: sellPackageId, paymentMethod: sellMethod };
    let instance: InstanceT;
    try {
      instance = await api.post<InstanceT>("/packages/sell", body, { idempotencyKey: sellKeys.current.get("sell", body) });
      sellKeys.current.done("sell");
    } catch (err) {
      if (err instanceof ApiError && !(err.status === 400 && err.errors.length)) sfe.set(err.detail || "Couldn't sell the package.");
      else if (err instanceof ApiError) sfe.fail(err, "Couldn't sell the package.");
      else sfe.set("Couldn't reach the server. The sale may or may not have gone through: check Active patient packages before trying again.");
      setSelling(false);
      return;
    }
    // The invoice exists from here on: stay in the wizard on the "Invoice & card" step.
    setSold(instance);
    setSellStep(5);
    if (can("billing:view")) {
      try { setSoldInvoice(await api.get<SoldInvoice>(`/billing/invoices/${instance.invoiceId}`)); } catch { /* the sale stands; the summary falls back to the instance */ }
    }
    await loadAll();
    setSelling(false);
  }

  return (
    <div className={styles.card}>
      <h2 className={styles.cardTitle}>Sell a package</h2>
      <ol className={styles.steps} aria-label="Sell package steps">
        {SELL_STEPS.map((label, i) => (
          <li key={label} className={i === sellStep ? styles.stepOn : i < sellStep ? styles.stepDone : styles.step}
            aria-current={i === sellStep ? "step" : undefined}>
            <span className={styles.stepNum}>{i < sellStep ? "✓" : i + 1}</span> {label}
          </li>
        ))}
      </ol>

      {sellStep === 0 && (
        <div className={styles.field}>
          <label className={styles.label} htmlFor="sell-patient">Select the patient</label>
          {sellPatient ? (
            <div className={styles.itemLine}>
              <span>{sellPatient.name} · {sellPatient.phone}</span>
              <button type="button" className={styles.btnSmall} onClick={() => setSellPatient(null)}>Change</button>
            </div>
          ) : (
            <>
              <input id="sell-patient" className={styles.input} placeholder="Search patient by name or phone…" value={sellQuery} onChange={(e) => onSellQueryChange(e.target.value)} />
              {sellResults.map((p) => (
                <button type="button" key={p.id} className={`${styles.btn} ${styles.resultBtn}`}
                  onClick={() => setSellPatient(p)}>{p.name} · {p.phone} · {p.mrn}</button>
              ))}
            </>
          )}
          <p className={styles.muted}>Not registered yet? Register the patient at Arrivals first, then come back.</p>
        </div>
      )}

      {sellStep === 1 && (
        <div className={styles.field}>
          <div className={styles.label}>Select the package</div>
          {onSale.length === 0 && <div className={styles.empty}>No packages are on sale.</div>}
          {onSale.map((p) => (
            <button type="button" key={p.id} aria-pressed={sellPackageId === p.id}
              className={`${sellPackageId === p.id ? styles.btnPrimary : styles.btn} ${styles.resultBtn}`}
              onClick={() => setSellPackageId(p.id)}>
              {p.name} — {money(p.price)} ({p.validityDays}d){p.saveAmount > 0 ? ` · save ${p.savePercent}%` : ""}
            </button>
          ))}
        </div>
      )}

      {sellStep === 2 && sellPackage && sellPatient && (() => {
        const conflict = instances.find((i) => i.patientId === sellPatient.id && i.packageId === sellPackage.id && BLOCKING_STATUSES.includes(i.status));
        const blocked = !!conflict;
        const doctorNames = sellPackage.eligibleDoctorIds.map((id) => staff.find((s) => s.id === id)?.name).filter(Boolean);
        return (
          <>
            <div className={styles.flagRow}>
              {blocked
                ? <StatusPill tone="danger" label="Not eligible" />
                : <StatusPill tone="ok" label="Eligibility confirmed" />}
              <span className={styles.muted}>
                {conflict
                  ? `${sellPatient.name} already has a ${sellPackage.name} (${conflict.status}). Another copy can be sold only after it is completed or refunded.`
                  : `No conflicting package for ${sellPatient.name}.`}
              </span>
            </div>
            {sellPackage.doctorLeaveWarning && <div className={styles.bannerWarn}>{sellPackage.doctorLeaveWarning}</div>}
            <h3 className={styles.cardTitle}>Eligibility rules</h3>
            <div className={styles.itemLine}><span>Doctors</span><span>{doctorNames.length > 0 ? doctorNames.join(", ") : "All doctors"}</span></div>
            <div className={styles.itemLine}><span>Validity starts</span><span>{sellPackage.validityStarts === "first_session" ? "First session" : "Purchase date"}</span></div>
            <div className={styles.itemLine}><span>Validity</span><span>{sellPackage.validityDays} days + {sellPackage.graceDays} days grace</span></div>
          </>
        );
      })()}

      {sellStep === 3 && sellPackage && (
        <>
          <div className={styles.statGrid}>
            <div className={styles.statCard}><div className={styles.statLabel}>Separate value</div><div className={styles.statValue}>{money(sellPackage.listValue)}</div></div>
            <div className={styles.statCard}><div className={styles.statLabel}>Package price</div><div className={styles.statValue}>{money(sellPackage.price)}</div><div className={styles.muted}>{sellPackage.taxInclusive ? "incl. tax" : "excl. tax"}</div></div>
            <div className={styles.statCard}><div className={styles.statLabel}>Patient saves</div><div className={styles.statValue}>{money(sellPackage.saveAmount)}</div><div className={styles.muted}>{sellPackage.savePercent}%</div></div>
          </div>
          <h3 className={styles.cardTitle}>Included items</h3>
          {sellPackage.items.map((i) => (
            <div key={i.id} className={styles.itemLine}><span>{i.name} × {i.quantity}</span><span>{money(i.listValue)}</span></div>
          ))}
          {sellPatient && <p className={styles.muted}>Selling to {sellPatient.name} · {sellPatient.phone}</p>}
        </>
      )}

      {sellStep === 4 && sellPackage && (
        <>
          <div className={styles.field}>
            <div className={styles.label}>Payment method</div>
            <div className={styles.methodRow}>
              {PAYMENT_METHODS.map((v) => (
                <button type="button" key={v} aria-pressed={sellMethod === v} className={sellMethod === v ? styles.btnPrimary : styles.btn} onClick={() => setSellMethod(v)}>
                  {sellMethod === v ? "✓ " : ""}{paymentMethodLabel(v, getIdentity()?.tenantRegion)}
                </button>
              ))}
            </div>
            <p className={styles.muted}>Full payment only in this build — scheduled instalments are a later phase.</p>
          </div>
          <div className={styles.itemLine}>
            <span>Due now</span>
            <strong>{money(sellPackage.price)}{sellPackage.taxInclusive ? " incl. tax" : " + tax at each item's rate"}</strong>
          </div>
          <div className={styles.flagRow}>
            {sellMethod ? <StatusPill tone="ok" label="Ready to collect" /> : <StatusPill tone="warn" label="Choose a method" />}
          </div>
        </>
      )}

      {sellStep === 5 && sold && <SoldSummary sold={sold} soldInvoice={soldInvoice} />}

      <ErrorSummary fe={sfe} className={styles.errorState} />
      <div className={styles.actions}>
        {sellStep < 5 && <button type="button" className={styles.btn} onClick={onCancel}>Cancel</button>}
        {sellStep > 0 && sellStep < 5 && <button type="button" className={styles.btn} onClick={() => { sfe.clear(); setSellStep(sellStep - 1); }}>Back</button>}
        {sellStep < 4 && (
          <button type="button" className={styles.btnPrimary}
            disabled={(sellStep === 0 && !sellPatient) || (sellStep >= 1 && !sellPackageId)
              || (sellStep === 2 && instances.some((i) => i.patientId === sellPatient?.id && i.packageId === sellPackageId && BLOCKING_STATUSES.includes(i.status)))}
            onClick={() => setSellStep(sellStep + 1)}>Continue</button>
        )}
        {sellStep === 4 && (
          <button type="button" className={styles.btnPrimary} disabled={selling || !sellMethod} onClick={() => void submitSale()}>
            {selling ? "Selling…" : sellMethod ? "Sell package" : "Choose a payment method"}
          </button>
        )}
        {sellStep === 5 && sold && (
          <button type="button" className={styles.btnPrimary} onClick={() => onOpenInstance(sold.id)}>Open the active package</button>
        )}
      </div>
    </div>
  );
}

/** Step 6: the server's invoice (billing:view) or, without it, the instance's own price, tax and allocation. */
function SoldSummary({ sold, soldInvoice }: { sold: InstanceT; soldInvoice: SoldInvoice | null }) {
  return (
    <>
      <div className={styles.flagRow}>
        <StatusPill tone="ok" label="Sold — invoice issued" />
        <span className={styles.muted}>{sold.packageName} · {sold.patientName}</span>
      </div>
      {soldInvoice ? (
        <div className={styles.statGrid}>
          <div className={styles.statCard}><div className={styles.statLabel}>Net</div><div className={styles.statValue}>{formatMoney(soldInvoice.subtotal, soldInvoice.currency)}</div></div>
          <div className={styles.statCard}><div className={styles.statLabel}>Tax</div><div className={styles.statValue}>{formatMoney(soldInvoice.tax, soldInvoice.currency)}</div></div>
          <div className={styles.statCard}><div className={styles.statLabel}>Total</div><div className={styles.statValue}>{formatMoney(soldInvoice.total, soldInvoice.currency)}</div><div className={styles.muted}>paid {formatMoney(soldInvoice.paid, soldInvoice.currency)}</div></div>
          <div className={styles.statCard}><div className={styles.statLabel}>Invoice</div><div className={styles.statValue}>{soldInvoice.invoiceNumber}</div></div>
        </div>
      ) : (
        <div className={styles.statGrid}>
          <div className={styles.statCard}><div className={styles.statLabel}>Package price</div><div className={styles.statValue}>{money(sold.soldPrice)}</div></div>
          <div className={styles.statCard}><div className={styles.statLabel}>Tax</div><div className={styles.statValue}>{money(sold.soldTax)}</div></div>
          <div className={styles.statCard}><div className={styles.statLabel}>Invoice</div><div className={styles.statValue}>{sold.invoiceNumber}</div></div>
        </div>
      )}
      <h3 className={styles.cardTitle}>{soldInvoice ? "Invoice lines" : "Tax allocated by list-price ratio"}</h3>
      {soldInvoice ? (
        // One invoice line per package item: the server's ex-tax line total and its tax at the item's rate.
        soldInvoice.lineItems.map((l, n) => (
          <div key={n} className={styles.itemLine}>
            <span>
              {l.chargeName} × {l.quantity}
              <span className={styles.muted}> · net {formatMoney(l.lineTotal, soldInvoice.currency)} · {l.taxRatePercent > 0 ? `tax ${l.taxRatePercent}%` : "exempt"}</span>
            </span>
            <span>{formatMoney(Math.round(l.lineTotal * l.taxRatePercent) / 100, soldInvoice.currency)} tax</span>
          </div>
        ))
      ) : (() => {
        const listTotal = sold.items.reduce((sum, i) => sum + i.unitListPrice * i.quantityTotal, 0);
        return sold.items.map((i) => (
          <div key={i.id} className={styles.itemLine}>
            <span>
              {i.name}
              <span className={styles.muted}> · {listTotal > 0 ? Math.round((i.unitListPrice * i.quantityTotal / listTotal) * 100) : 0}% of list · {i.taxRatePercent > 0 ? `tax ${i.taxRatePercent}%` : "exempt"}</span>
            </span>
            <span>{money(i.allocatedPrice)}</span>
          </div>
        ));
      })()}
      <h3 className={styles.cardTitle}>Digital package card</h3>
      <p className={styles.muted}>Sending the card on WhatsApp isn&apos;t available yet. Open the active package to see its sessions and ledger.</p>
    </>
  );
}
