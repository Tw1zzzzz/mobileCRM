import { signOutAction } from "@/app/staff/actions";
import styles from "@/app/staff/staff.module.css";

export function SignOutButton() {
  return (
    <form action={signOutAction}>
      <button type="submit" className={styles.ghost}>
        Выйти
      </button>
    </form>
  );
}
