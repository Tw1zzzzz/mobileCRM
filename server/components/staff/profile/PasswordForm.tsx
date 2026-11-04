"use client";

import { useActionState } from "react";
import { changePasswordAction } from "@/app/staff/actions";
import { IDLE } from "@/lib/staff/form-state";
import { Field } from "./Field";
import { FormMessage } from "./FormMessage";
import styles from "./profile.module.css";

/** «Пароль»: the current password, the new one twice. The other sessions are signed out; this one stays. */
export function PasswordForm() {
  const [state, action, pending] = useActionState(changePasswordAction, IDLE);
  return (
    <section className={styles.section} aria-labelledby="profile-password">
      <h2 id="profile-password" className={styles.sectionTitle}>
        Пароль
      </h2>
      <form action={action} className={styles.form} noValidate>
        <Field id="profile-current-password" label="Текущий пароль" name="current" type="password" autoComplete="current-password" />
        <Field id="profile-new-password" label="Новый пароль" name="next" type="password" autoComplete="new-password" />
        <Field id="profile-confirm-password" label="Повторите пароль" name="confirm" type="password" autoComplete="new-password" />
        <FormMessage state={state} />
        <div className={styles.actions}>
          <button type="submit" className={styles.primary} disabled={pending}>
            Сменить пароль
          </button>
        </div>
      </form>
    </section>
  );
}
