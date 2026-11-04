import type { FormState } from "@/lib/staff/form-state";
import styles from "./profile.module.css";

/** The outcome of the last submit: the app's error text, or the notice («Сохранено», «Почта изменена», ...). */
export function FormMessage({ state }: { state: FormState }) {
  if (state.status === "error") {
    return (
      <p role="alert" className={styles.error}>
        {state.message}
      </p>
    );
  }
  if (state.status === "done" && state.message !== "") {
    return (
      <p role="status" className={styles.notice}>
        {state.message}
      </p>
    );
  }
  return null;
}
