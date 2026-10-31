import type { FoodData } from "@/lib/staff/card-food";
import { NO_DATA } from "@/lib/staff/overview-format";
import styles from "./card.module.css";

function Placeholder() {
  return (
    <span className={styles.thumb} role="img" aria-label="Фото блюда">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <circle cx="12" cy="13" r="7" />
        <path d="M5 13h14" />
      </svg>
    </span>
  );
}

/** «Питание»: the diary of the period; a meal with a stored photo shows it through the staff photo route. */
export function FoodTab({ food }: { food: FoodData }) {
  return (
    <div className={styles.body}>
      <span className={styles.sub}>{food.summary}</span>
      <div className={styles.table}>
        <div role="table" aria-label="Дневник питания" className={styles.tableInner}>
          <div role="row" className={`${styles.row} ${styles.rowFood} ${styles.head}`}>
            <span role="columnheader">Фото</span>
            <span role="columnheader">Дата</span>
            <span role="columnheader">Блюдо</span>
            <span role="columnheader">Калорийность</span>
          </div>
          {food.rows.length === 0 ? (
            <div role="row" className={styles.row}>
              <span role="cell">{NO_DATA}</span>
            </div>
          ) : (
            food.rows.map((row, i) => (
              <div key={i} role="row" className={`${styles.row} ${styles.rowFood}`}>
                <span role="cell">
                  {row.photo === null ? (
                    <Placeholder />
                  ) : (
                    // eslint-disable-next-line @next/next/no-img-element -- a private, already small JPEG behind our own route
                    <img src={row.photo} alt="Фото блюда" width={44} height={44} loading="lazy" className={styles.thumb} />
                  )}
                </span>
                <span role="cell">{row.date}</span>
                <span role="cell" className={styles.strong}>{row.name}</span>
                <span role="cell" className={styles.heavy}>{row.kcal}</span>
              </div>
            ))
          )}
        </div>
      </div>
      {food.total > food.rows.length && (
        <p className={styles.cut}>Показаны последние {food.rows.length} из {food.total}</p>
      )}
    </div>
  );
}
