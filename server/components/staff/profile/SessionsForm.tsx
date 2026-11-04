import { signOutEverywhereAction } from "@/app/staff/actions";
import styles from "./profile.module.css";

/** «Сеансы»: ends every session of the person, this browser and the app included. */
export function SessionsForm() {
  return (
    <section className={styles.section} aria-labelledby="profile-sessions">
      <h2 id="profile-sessions" className={styles.sectionTitle}>
        Сеансы
      </h2>
      <p className={styles.hint}>
        Завершит вход во всех браузерах и в приложении, включая этот. Войти нужно будет заново.
      </p>
      <form action={signOutEverywhereAction} className={styles.form}>
        <div className={styles.actions}>
          <button type="submit" className={styles.secondary}>
            Выйти на всех устройствах
          </button>
        </div>
      </form>
    </section>
  );
}
