"use client";

import Link from "next/link";
import { useActionState, useState } from "react";
import { signInAction } from "@/app/staff/actions";
import { IDLE } from "@/lib/staff/form-state";
import styles from "../auth.module.css";

export function LoginForm() {
  const [state, action, pending] = useActionState(signInAction, IDLE);
  // Controlled: React clears uncontrolled fields after every action, and the email should survive a typo in the password.
  const [email, setEmail] = useState("");
  return (
    <form action={action} className={styles.form} noValidate>
      <div className={styles.field}>
        <label htmlFor="email">Email</label>
        <input id="email" name="email" type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} className={styles.input} />
      </div>
      <div className={styles.field}>
        <label htmlFor="password">Пароль</label>
        <input id="password" name="password" type="password" autoComplete="current-password" className={styles.input} />
      </div>
      {state.status === "error" && (
        <p role="alert" className={styles.error}>
          {state.message}
        </p>
      )}
      <button type="submit" className={styles.primary} disabled={pending}>
        Войти
      </button>
      <div className={styles.links}>
        <Link href="/staff/reset">Забыли пароль?</Link>
      </div>
    </form>
  );
}
