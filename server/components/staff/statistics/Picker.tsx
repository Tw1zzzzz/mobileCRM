import Link from "next/link";
import { STAT_METRICS } from "@/lib/staff/statistics";
import { DEFAULT_METRICS, MAX_METRICS, statsHref, toggleMetric, type StatsParams } from "@/lib/staff/statistics-page";
import styles from "./statistics.module.css";

type Group = "state" | "game";

const GROUPS: readonly { group: Group; title: string; label: string }[] = [
  { group: "state", title: "Состояние", label: "Метрики состояния" },
  { group: "game", title: "Игра · Faceit", label: "Игровые метрики" },
];

function chipClass(group: Group, on: boolean, off: boolean): string {
  if (on) return `${styles.mchip} ${group === "state" ? styles.mchipOnState : styles.mchipOnGame}`;
  return off ? `${styles.mchip} ${styles.mchipOff}` : styles.mchip;
}

/**
 * «Выберите метрики для сравнения»: every chip is a link to the selection with that metric switched, so the
 * choice lives in the URL. With eight chosen the other chips cannot add a ninth.
 */
export function Picker({ params }: { params: StatsParams }) {
  const full = params.metrics.length >= MAX_METRICS;
  return (
    <section className={styles.card}>
      <div className={styles.cardHead}>
        <div className={styles.cardTitles}>
          <h2 className={styles.cardTitle}>Выберите метрики для сравнения</h2>
          <span className={styles.sub}>{`Слева состояние, справа игра. Выбрано ${params.metrics.length} из ${MAX_METRICS}`}</span>
        </div>
        <Link href={statsHref(params, { metrics: DEFAULT_METRICS })} scroll={false} className={styles.ghost}>
          Сбросить выбор
        </Link>
      </div>
      <div className={styles.pickerGroups}>
        {GROUPS.map(({ group, title, label }) => (
          <div key={group} className={styles.pickerGroup}>
            <span className={`${styles.groupTitle} ${group === "state" ? styles.groupState : styles.groupGame}`}>
              <span className={`${styles.dot} ${group === "state" ? styles.dotState : styles.dotGame}`} />
              {title}
            </span>
            <div role="group" aria-label={label} className={styles.metricChips}>
              {STAT_METRICS.filter((m) => m.group === group).map((m) => {
                const on = params.metrics.includes(m.id);
                if (!on && full) {
                  return (
                    <span key={m.id} aria-disabled="true" className={chipClass(group, false, true)}>
                      {m.label}
                    </span>
                  );
                }
                return (
                  <Link
                    key={m.id}
                    aria-current={on ? "true" : undefined}
                    href={statsHref(toggleMetric(params, m.id))}
                    scroll={false}
                    className={chipClass(group, on, false)}
                  >
                    {m.label}
                  </Link>
                );
              })}
            </div>
          </div>
        ))}
      </div>
      {full && (
        <span role="status" className={styles.limit}>
          Можно выбрать до 8 метрик — снимите одну, чтобы добавить другую
        </span>
      )}
    </section>
  );
}
