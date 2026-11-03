"use client";

import { useState, type ReactNode } from "react";
import styles from "./lineups.module.css";

interface DetailActionsProps {
  title: string;
  id: string;
  /** The server action that deletes an entry; the person is sent back to the list by it. */
  deleteAction: (form: FormData) => void | Promise<void>;
  /** The filters to come back to, as plain form values. */
  returnTo: Readonly<Record<string, string>>;
  /** The favourite toggle and the edit link: server-rendered, shown beside «Удалить». */
  children: ReactNode;
}

/** «★ В избранном», «Редактировать», «Удалить»; «Удалить» asks first, in place, as on the board. */
export function DetailActions({ title, id, deleteAction, returnTo, children }: DetailActionsProps) {
  const [asking, setAsking] = useState(false);
  if (!asking) {
    return (
      <div className={styles.actions}>
        {children}
        <button className={styles.btnDanger} type="button" onClick={() => setAsking(true)}>Удалить</button>
      </div>
    );
  }
  return (
    <form action={deleteAction} className={styles.confirm}>
      <input type="hidden" name="id" value={id} />
      {Object.entries(returnTo).map(([name, value]) => <input key={name} type="hidden" name={name} value={value} />)}
      <span className={styles.confirmText}>Удалить «{title}»? Запись пропадёт у всех игроков команды</span>
      <div className={styles.actions}>
        <button className={styles.btnDanger} type="submit">Удалить</button>
        <button className={styles.btnGhost} type="button" onClick={() => setAsking(false)}>Отмена</button>
      </div>
    </form>
  );
}
