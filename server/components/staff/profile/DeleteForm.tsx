"use client";

import { useActionState } from "react";
import { deleteAccountAction } from "@/app/staff/actions";
import { IDLE } from "@/lib/staff/form-state";
import { Field } from "./Field";
import { FormMessage } from "./FormMessage";
import styles from "./profile.module.css";

/** «Удаление аккаунта»: the password is the confirmation; a successful delete ends in the sign-in page. */
export function DeleteForm() {
  const [state, action, pending] = useActionState(deleteAccountAction, IDLE);
  return (
    <section className={`${styles.section} ${styles.sectionDanger}`} aria-labelledby="profile-delete">
      <h2 id="profile-delete" className={styles.sectionTitle}>
        Удаление аккаунта
      </h2>
      <p className={styles.hint}>Аккаунт и все данные будут удалены без возможности восстановления.</p>
      <form action={action} className={styles.form} noValidate>
        <Field id="profile-delete-password" label="Пароль" name="password" type="password" autoComplete="current-password" />
        <FormMessage state={state} />
        <div className={styles.actions}>
          <button type="submit" className={styles.danger} disabled={pending}>
            Удалить аккаунт
          </button>
        </div>
      </form>
    </section>
  );
}
