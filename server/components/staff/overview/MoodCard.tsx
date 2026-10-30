import { donutArcs } from "@/lib/staff/overview-charts";
import { NO_DATA, fmt1, records } from "@/lib/staff/overview-format";
import type { OverviewData } from "@/lib/staff/metrics";
import { Pill } from "./Pill";
import styles from "./overview.module.css";

const CATEGORY_COLORS = ["#3FD0FF", "#1F6BFF", "#7C8BB0", "#FF8A3D"] as const;

interface MoodCardProps {
  mood: OverviewData["mood"];
  /** «вся команда» or a nickname. */
  whoLabel: string;
  isTeam: boolean;
}

/** «Настроение»: the average, and how the last week's records split into the four categories. */
export function MoodCard({ mood, whoLabel, isTeam }: MoodCardProps) {
  const arcs = donutArcs(mood.categories);
  const aria = `Распределение настроения: ${mood.categories.map((c) => `${c.name.toLowerCase()} ${c.percent}%`).join(", ")}`;
  return (
    <article className={styles.card}>
      <div className={styles.cardHead}>
        <div className={styles.cardTitles}>
          <h3 className={styles.cardTitle}>Настроение</h3>
          <span className={styles.cardSub}>
            {records(mood.count)} за 7 дней · {whoLabel}
          </span>
        </div>
        <Pill pill={mood.pill} />
      </div>
      {mood.average === null ? (
        <div className={styles.cardBody}>
          <div className={styles.noData}>{NO_DATA}</div>
        </div>
      ) : (
        <div className={styles.moodBody}>
          <div className={styles.donut}>
            <svg width="200" height="200" viewBox="0 0 200 200" role="img" aria-label={aria}>
              <circle cx="100" cy="100" r="80" fill="none" stroke="#1E2740" strokeWidth="18" />
              <g transform="rotate(-90 100 100)" fill="none" strokeWidth="18" strokeLinecap="round">
                {arcs.map((arc, i) => (
                  <circle
                    key={mood.categories[i].name}
                    cx="100"
                    cy="100"
                    r="80"
                    stroke={CATEGORY_COLORS[i]}
                    strokeDasharray={arc.dasharray}
                    strokeDashoffset={arc.dashoffset}
                    strokeOpacity={arc.visible ? 1 : 0}
                  />
                ))}
              </g>
            </svg>
            <div className={styles.donutCenter}>
              <span className={styles.donutValue}>{fmt1(mood.average)}</span>
              <span className={styles.donutLabel}>{isTeam ? "среднее по команде" : "среднее"}</span>
            </div>
          </div>
          <div className={styles.cats}>
            <span className={styles.catsTitle}>По категориям</span>
            {mood.categories.map((c, i) => (
              <div className={styles.cat} key={c.name}>
                <span className={styles.catDot} style={{ background: CATEGORY_COLORS[i] }} />
                <span className={styles.catName}>
                  <span className={styles.catNameMain}>{c.name}</span>
                  <span className={styles.catNote}>{c.range}</span>
                </span>
                <span className={styles.catNums}>
                  <span className={styles.catPercent}>{c.percent}%</span>
                  <span className={styles.catNote}>{records(c.count)}</span>
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </article>
  );
}
