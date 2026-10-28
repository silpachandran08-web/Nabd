import type { ReactNode } from "react";
import styles from "../../(clinic)/checkout/checkout.module.css";

/** One charge button of the checkout picker; also the setup wizard's billing-screen preview (FIN-017). No onClick = shown, not offered. */
export default function ChargeButton({ label, price, onClick, dimmed = false }: { label: ReactNode; price: ReactNode; onClick?: () => void; dimmed?: boolean }) {
  const body = (
    <>
      <span className={styles.chargeName}>{label}</span>
      <span className={styles.chargePrice}>{price}</span>
    </>
  );
  return onClick
    ? <button type="button" className={styles.chargeItem} onClick={onClick}>{body}</button>
    : <div className={styles.chargeItem} style={{ cursor: "default", opacity: dimmed ? 0.7 : 1 }}>{body}</div>;
}
