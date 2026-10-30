import { ResetForm } from "./ResetForm";
import styles from "../auth.module.css";

export const metadata = { title: "Сброс пароля — кабинет стаффа" };

export default function ResetPage() {
  return (
    <div className={styles.page}>
      <main className={styles.card}>
        <h1 className={styles.title}>Сброс пароля</h1>
        <ResetForm />
      </main>
    </div>
  );
}
