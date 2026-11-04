"use client";

import { useActionState, useState } from "react";
import { emailChangeAction } from "@/app/staff/actions";
import { IDLE } from "@/lib/staff/form-state";
import { Field } from "./Field";
import { FormMessage } from "./FormMessage";
import styles from "./profile.module.css";

interface EmailFormProps {
  current: string;
  /** The new address a code was sent to and is still waiting for; the code step shows while it is set. */
  pending: string | null;
}

/** «Почта»: the current address, «Новая почта» + «Текущий пароль» → «Получить код», then «Код из письма» → «Подтвердить». */
export function EmailForm({ current, pending }: EmailFormProps) {
  // One action for both steps, so one message: the page re-renders after each, and `pending` decides the step.
  const [state, action, isPending] = useActionState(emailChangeAction, IDLE);
  const [email, setEmail] = useState("");
  return (
    <section className={styles.section} aria-labelledby="profile-email">
      <h2 id="profile-email" className={styles.sectionTitle}>
        Почта
      </h2>
      <p className={styles.current}>
        <span>Текущая почта</span>
        <span className={styles.currentValue}>{current}</span>
      </p>
      <form action={action} className={styles.form} noValidate>
        <input type="hidden" name="step" value="request" />
        <Field id="profile-new-email" label="Новая почта" name="email" type="email" autoComplete="email" value={email} onChange={setEmail} />
        <Field id="profile-email-password" label="Текущий пароль" name="password" type="password" autoComplete="current-password" />
        <div className={styles.actions}>
          <button type="submit" className={styles.secondary} disabled={isPending}>
            Получить код
          </button>
        </div>
      </form>
      {pending !== null && (
        <form action={action} className={`${styles.form} ${styles.step}`} noValidate>
          <input type="hidden" name="step" value="confirm" />
          <p className={styles.hint}>Код отправлен на {pending}</p>
          <Field id="profile-email-code" label="Код из письма" name="code" autoComplete="one-time-code" />
          <div className={styles.actions}>
            <button type="submit" className={styles.primary} disabled={isPending}>
              Подтвердить
            </button>
          </div>
        </form>
      )}
      <FormMessage state={state} />
    </section>
  );
}
