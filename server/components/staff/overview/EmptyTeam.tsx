import styles from "./overview.module.css";

/** A team nobody has joined yet: the player code to send out, in the place of the numbers. */
export function EmptyTeam({ playerCode }: { playerCode: string }) {
  return (
    <section className={styles.panel}>
      <div className={styles.panelHead}>
        <h2 className={styles.panelTitle}>Недельный обзор</h2>
      </div>
      <div className={styles.emptyTeam}>
        <p className={styles.emptyTitle}>В команде пока нет игроков</p>
        <span className={styles.codeLabel}>Код игрока</span>
        <code className={styles.code}>{playerCode}</code>
        <span className={styles.hint}>
          Отправьте этот код игрокам: они введут его в приложении Pulse и появятся в команде.
        </span>
      </div>
    </section>
  );
}
