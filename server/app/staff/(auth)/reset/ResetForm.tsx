"use client";

import Link from "next/link";
import { useActionState, useState } from "react";
import { confirmResetAction, requestResetAction } from "@/app/staff/actions";
import { IDLE } from "@/lib/staff/form-state";
import styles from "../auth.module.css";

function Problem({ message }: { message: string }) {
  return (
    <p role="alert" className={styles.error}>
      {message}
    </p>
  );
}

function RequestStep({ action, pending, error }: { action: (form: FormData) => void; pending: boolean; error: string | null }) {
  const [email, setEmail] = useState("");
  return (
    <form action={action} className={styles.form} noValidate>
      <div className={styles.field}>
        <label htmlFor="email">Email</label>
        <input id="email" name="email" type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} className={styles.input} />
      </div>
      {error !== null && <Problem message={error} />}
      <button type="submit" className={styles.primary} disabled={pending}>
        Получить код
      </button>
    </form>
  );
}

function ConfirmStep({ email }: { email: string }) {
  const [state, action, pending] = useActionState(confirmResetAction, IDLE);
  const [code, setCode] = useState("");
  return (
    <form action={action} className={styles.form} noValidate>
      <p className={styles.sub}>Мы отправили 6-значный код на {email}</p>
      <input type="hidden" name="email" value={email} />
      <div className={styles.field}>
        <label htmlFor="code">Код из письма</label>
        <input id="code" name="code" inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} onChange={(e) => setCode(e.target.value)} className={styles.input} />
      </div>
      <div className={styles.field}>
        <label htmlFor="password">Новый пароль</label>
        <input id="password" name="password" type="password" autoComplete="new-password" className={styles.input} />
      </div>
      <div className={styles.field}>
        <label htmlFor="confirm">Повторите пароль</label>
        <input id="confirm" name="confirm" type="password" autoComplete="new-password" className={styles.input} />
      </div>
      {state.status === "error" && <Problem message={state.message} />}
      <button type="submit" className={styles.primary} disabled={pending}>
        Сменить пароль
      </button>
    </form>
  );
}

/** «Забыли пароль?»: the email first, then the emailed code and the new password, which signs in. */
export function ResetForm() {
  const [requested, requestAction, requesting] = useActionState(requestResetAction, IDLE);
  return (
    <>
      {requested.status === "codeSent" ? (
        <ConfirmStep email={requested.email} />
      ) : (
        <RequestStep
          action={requestAction}
          pending={requesting}
          error={requested.status === "error" ? requested.message : null}
        />
      )}
      <div className={styles.links}>
        <Link href="/staff/login">Назад ко входу</Link>
      </div>
    </>
  );
}
