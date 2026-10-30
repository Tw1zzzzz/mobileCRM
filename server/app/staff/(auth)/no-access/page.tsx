import { redirect } from "next/navigation";
import { SignOutButton } from "@/components/staff/SignOutButton";
import { getSessionState } from "@/lib/staff/current";
import styles from "../auth.module.css";

export const metadata = { title: "Нет доступа — кабинет стаффа" };

/** Where someone who is signed in but not staff of a team lands: a message and a way out, no data. */
export default async function NoAccessPage() {
  const state = await getSessionState();
  if (state.kind === "none") redirect("/staff/login");
  if (state.kind === "staff") redirect("/staff/overview");
  return (
    <div className={styles.page}>
      <main className={styles.card}>
        <h1 className={styles.title}>Кабинет доступен только стаффу команды</h1>
        <SignOutButton />
      </main>
    </div>
  );
}
