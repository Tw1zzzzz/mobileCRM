"use client";

import { useActionState, useState } from "react";
import { saveProfileAction } from "@/app/staff/actions";
import { IDLE } from "@/lib/staff/form-state";
import { Field } from "./Field";
import { FormMessage } from "./FormMessage";
import styles from "./profile.module.css";

/** «Основное»: nickname and Faceit link. */
export function MainForm({ nickname, faceitUrl }: { nickname: string; faceitUrl: string | null }) {
  const [state, action, pending] = useActionState(saveProfileAction, IDLE);
  // Controlled: React clears uncontrolled fields after every action, and a refused value should stay to be fixed.
  const [nick, setNick] = useState(nickname);
  const [faceit, setFaceit] = useState(faceitUrl ?? "");
  return (
    <section className={styles.section} aria-labelledby="profile-main">
      <h2 id="profile-main" className={styles.sectionTitle}>
        Основное
      </h2>
      <form action={action} className={styles.form} noValidate>
        <Field id="profile-nickname" label="Ник" name="nickname" autoComplete="nickname" value={nick} onChange={setNick} />
        <Field id="profile-faceit" label="Ссылка на Faceit" name="faceitUrl" type="url" autoComplete="url" value={faceit} onChange={setFaceit} />
        <FormMessage state={state} />
        <div className={styles.actions}>
          <button type="submit" className={styles.primary} disabled={pending}>
            Сохранить
          </button>
        </div>
      </form>
    </section>
  );
}
