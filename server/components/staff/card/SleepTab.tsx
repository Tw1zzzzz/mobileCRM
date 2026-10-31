import type { SleepData } from "@/lib/staff/card";
import { NO_DATA } from "@/lib/staff/overview-format";
import styles from "./card.module.css";

/** «Сон»: the average night of the period and the last ten nights; under 7 hours is marked. */
export function SleepTab({ sleep }: { sleep: SleepData }) {
  return (
    <div className={styles.body}>
      <span className={styles.sub}>{sleep.summary}</span>
      <div className={styles.table}>
        <div role="table" aria-label="Дневник сна" className={styles.tableInner}>
          <div role="row" className={`${styles.row} ${styles.rowSleep} ${styles.head}`}>
            <span role="columnheader">Дата сна</span>
            <span role="columnheader">Начало</span>
            <span role="columnheader">Окончание</span>
            <span role="columnheader">Длительность</span>
          </div>
          {sleep.rows.length === 0 ? (
            <div role="row" className={styles.row}>
              <span role="cell">{NO_DATA}</span>
            </div>
          ) : (
            sleep.rows.map((row, i) => (
              <div key={i} role="row" className={`${styles.row} ${styles.rowSleep}`}>
                <span role="cell" className={styles.strong}>{row.date}</span>
                <span role="cell">{row.start}</span>
                <span role="cell">{row.end}</span>
                <span role="cell" className={`${styles.heavy} ${row.short ? `${styles.st} ${styles.stBad}` : ""}`}>
                  {row.duration}
                </span>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
