import type { Booking } from "../lib/patientApi";
import { formatWhen, type T } from "./util";
import styles from "./linkflow.module.css";

export default function BookingView({ booking, t, locale }: { booking: Booking; t: T; locale: string }) {
  return (
    <section className={styles.card}>
      <h1 className={styles.h1}>{t("bookingTitle")}</h1>
      <dl className={styles.dl}>
        <div><dt>{t("bookingWith")}</dt><dd>{booking.doctorName}</dd></div>
        <div><dt>{t("bookingWhen")}</dt><dd>{formatWhen(booking.startsAt, locale, { time: true, timeZone: booking.timeZone })}</dd></div>
        <div><dt>{t("bookingWhere")}</dt><dd>{booking.clinicName}{booking.address ? `, ${booking.address}` : ""}</dd></div>
        <div><dt>{t("bookingStatus")}</dt><dd>{t(`status_${booking.status}`)}</dd></div>
      </dl>
    </section>
  );
}
