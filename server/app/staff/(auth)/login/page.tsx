import Link from "next/link";
import { LoginForm } from "./LoginForm";
import styles from "../auth.module.css";

export const metadata = { title: "Вход — кабинет стаффа" };

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ verify?: string }> }) {
  const { verify } = await searchParams;
  if (verify !== undefined) {
    return (
      <div className={styles.page}>
        <main className={styles.card}>
          <h1 className={styles.title}>Подтвердите почту</h1>
          <p className={styles.sub}>Код пришёл на почту — введите его в приложении.</p>
          <div className={styles.links}>
            <Link href="/staff/login">Назад ко входу</Link>
          </div>
        </main>
      </div>
    );
  }
  return (
    <div className={styles.page}>
      <main className={styles.card}>
        <span className={styles.eyebrow}>Pulse</span>
        <h1 className={styles.title}>Кабинет стаффа</h1>
        <LoginForm />
      </main>
    </div>
  );
}
