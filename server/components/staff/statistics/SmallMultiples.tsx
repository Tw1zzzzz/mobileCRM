import type { MultipleView } from "@/lib/staff/statistics-view";
import { NO_DATA } from "@/lib/staff/overview-format";
import styles from "./statistics.module.css";

const COLORS = { state: "#3FD0FF", game: "#FFAA3C" } as const;

function Multiple({ m }: { m: MultipleView }) {
  const color = COLORS[m.group];
  return (
    <article className={styles.multiple}>
      <div className={styles.multipleHead}>
        <span className={styles.multipleName}>
          <span className={`${styles.dot} ${m.group === "state" ? styles.dotState : styles.dotGame}`} />
          {m.label}
        </span>
        {m.hasData && (
          <span className={styles.avg}>
            ср. по дням <b>{m.avg}</b>
          </span>
        )}
      </div>
      {m.hasData ? (
        <>
          <svg viewBox="0 0 300 90" width="100%" role="img" aria-label={m.aria} preserveAspectRatio="none" className={styles.chart}>
            <line x1="0" y1="45" x2="300" y2="45" stroke="#26304A" strokeDasharray="4 4" />
            {m.line !== "" && (
              <path d={m.line} fill="none" stroke={color} strokeWidth="2.5" strokeLinejoin="round" strokeLinecap="round" vectorEffect="non-scaling-stroke" />
            )}
            {m.dots !== "" && (
              <path d={m.dots} fill="none" stroke={color} strokeWidth="6" strokeLinecap="round" vectorEffect="non-scaling-stroke" />
            )}
          </svg>
          <div className={styles.foot}>
            <span>{m.from}</span>
            <span>{`мин ${m.min} · макс ${m.max}`}</span>
            <span>{m.to}</span>
          </div>
        </>
      ) : (
        <div className={styles.chartEmpty} role="img" aria-label={m.aria}>
          {NO_DATA}
        </div>
      )}
    </article>
  );
}

/** «Динамика выбранных метрик»: one 300×90 chart per metric, min–max scaled, with gaps for days without data. */
export function SmallMultiples({ multiples, team }: { multiples: readonly MultipleView[]; team: boolean }) {
  return (
    <section className={styles.card}>
      <div className={styles.cardTitles}>
        <h2 className={styles.cardTitle}>Динамика выбранных метрик</h2>
        <span className={styles.sub}>
          Каждая метрика — отдельный график, ось X — даты периода{team ? ". Вся команда: среднее по игрокам за день" : ""}
        </span>
      </div>
      <div className={styles.multiplesGrid}>
        {multiples.map((m) => (
          <Multiple key={m.id} m={m} />
        ))}
      </div>
    </section>
  );
}
