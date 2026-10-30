import { DAY_X, dayLabelLeft, energyDotsPath, energyLinePath, sleepBarsPath } from "@/lib/staff/overview-charts";
import { NO_DATA, arrow, fmt1, hm, weekdayCapital, weekdayShort } from "@/lib/staff/overview-format";
import type { OverviewData } from "@/lib/staff/metrics";
import { Pill } from "./Pill";
import styles from "./overview.module.css";

interface SleepEnergyCardProps {
  overview: OverviewData;
  whoLabel: string;
}

function hasValue(values: readonly (number | null)[]): boolean {
  return values.some((v) => v !== null);
}

/** «Сон и энергия»: seven days of sleep (bars) and energy (line), and three figures under the chart. */
export function SleepEnergyCard({ overview, whoLabel }: SleepEnergyCardProps) {
  const { sleep, energy, days } = overview;
  const hasChart = hasValue(sleep.daily) || hasValue(energy.daily);
  const aria = `Сон и энергия по дням: ${days
    .map((day, i) => {
      const hours = sleep.daily[i];
      const level = energy.daily[i];
      return `${weekdayCapital(day)} сон ${hours === null ? NO_DATA : `${fmt1(hours)} ч`}, энергия ${level === null ? NO_DATA : fmt1(level)}`;
    })
    .join("; ")}`;
  const energyUp = energy.trend === "up";

  return (
    <article className={styles.card}>
      <div className={styles.cardHead}>
        <div className={styles.cardTitles}>
          <h3 className={styles.cardTitle}>Сон и энергия</h3>
          <span className={styles.cardSub}>7 дней · {whoLabel}</span>
        </div>
        <Pill pill={sleep.pill} />
      </div>
      <div className={styles.cardBody}>
        {hasChart ? (
          <>
            <div className={styles.legend}>
              <span className={styles.legendItem}>
                <span className={styles.legendBar} />
                Сон, ч
              </span>
              <span className={styles.legendItem}>
                <span className={styles.legendLine} />
                Энергия, 0–10
              </span>
            </div>
            <div className={styles.chart}>
              <svg viewBox="0 0 640 240" role="img" aria-label={aria}>
                <defs>
                  <linearGradient id="slBar" x1="0" y1="1" x2="0" y2="0">
                    <stop offset="0" stopColor="#0066FF" />
                    <stop offset="1" stopColor="#00C8FF" />
                  </linearGradient>
                </defs>
                <g stroke="#26304A">
                  <line x1="30" y1="190" x2="630" y2="190" />
                  <line x1="30" y1="110" x2="630" y2="110" />
                  <line x1="30" y1="30" x2="630" y2="30" />
                </g>
                <g fill="#7D89A8" fontSize="12">
                  <text x="4" y="34">10</text>
                  <text x="10" y="114">5</text>
                  <text x="10" y="194">0</text>
                </g>
                <path d={sleepBarsPath(sleep.daily)} fill="url(#slBar)" opacity="0.9" />
                <path d={energyLinePath(energy.daily)} fill="none" stroke="#FFAA3C" strokeWidth="3" strokeLinejoin="round" strokeLinecap="round" />
                <path d={energyDotsPath(energy.daily)} fill="#070B17" stroke="#FFAA3C" strokeWidth="2.5" />
              </svg>
              <div className={styles.weekdays} aria-hidden="true">
                {days.map((day, i) => (
                  <span
                    key={day}
                    className={i === DAY_X.length - 1 ? `${styles.weekday} ${styles.weekdayToday}` : styles.weekday}
                    style={{ left: dayLabelLeft(i) }}
                  >
                    {weekdayCapital(day)}
                  </span>
                ))}
              </div>
            </div>
          </>
        ) : (
          <div className={styles.noData}>{NO_DATA}</div>
        )}
        <div className={styles.kpis}>
          <Kpi label="Средний сон" value={sleep.average === null ? null : hm(sleep.average)} />
          <Kpi
            label="Энергия сегодня"
            value={energy.today === null ? null : `${fmt1(energy.today)}${energy.trend === null ? "" : ` ${arrow(energy.trend)}`}`}
            tone={energy.trend === null ? undefined : energyUp ? "up" : "down"}
          />
          <Kpi
            label="Меньше всего сна"
            value={sleep.lowest === null ? null : `${weekdayShort(sleep.lowest.day)} · ${hm(sleep.lowest.hours)}`}
          />
        </div>
      </div>
    </article>
  );
}

function Kpi({ label, value, tone }: { label: string; value: string | null; tone?: "up" | "down" }) {
  const toneClass = tone === "up" ? styles.up : tone === "down" ? styles.down : "";
  return (
    <div className={styles.kpi}>
      <span className={styles.kpiLabel}>{label}</span>
      {value === null ? (
        <span className={styles.kpiValueMuted}>{NO_DATA}</span>
      ) : (
        <span className={`${styles.kpiValue} ${toneClass}`}>{value}</span>
      )}
    </div>
  );
}
