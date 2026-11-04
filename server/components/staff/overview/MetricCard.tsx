import { trend } from "@/lib/staff/overview-charts";
import { NO_DATA, arrow, fmt2, kcal, whole } from "@/lib/staff/overview-format";
import type { Day, MetricKey, WeeklyMetric } from "@/lib/staff/metrics";
import { trendPoints } from "@/lib/staff/overview-hover";
import { ChartHover } from "@/components/staff/ChartHover";
import { OpenLink } from "./OpenLink";
import { Pill } from "./Pill";
import styles from "./overview.module.css";

const TITLES: Record<MetricKey, string> = {
  readiness: "Готовность",
  cognitive: "Когнитивная форма",
  nutrition: "Питание, ккал в день",
  kd: "K/D",
  winRate: "Win-rate",
};

/** Where each metric is read in detail: the roster for readiness, «Статистика» for cognition, the team's meals. */
const HREFS: Record<MetricKey, string> = {
  readiness: "/staff/players",
  cognitive: "/staff/statistics",
  nutrition: "/staff/players?section=nutrition",
  kd: "/staff/game-stats",
  winRate: "/staff/game-stats",
};

const FORMAT: Record<MetricKey, (value: number) => string> = {
  readiness: whole,
  cognitive: whole,
  nutrition: kcal,
  kd: fmt2,
  winRate: (value) => `${whole(value)}%`,
};

const UP = { stroke: "#3FD0FF", fill: "rgba(31,139,255,0.22)" } as const;
const DOWN = { stroke: "#FF7A3D", fill: "rgba(255,122,61,0.18)" } as const;

interface MetricCardProps {
  metric: WeeklyMetric;
  whoLabel: string;
  /** The 7 days of the trend, oldest first, and the last of them. */
  days: readonly Day[];
  today: Day;
}

/** One weekly metric: the value, the change against the previous week, and a 7-day trend. */
export function MetricCard({ metric, whoLabel, days, today }: MetricCardProps) {
  const title = TITLES[metric.key];
  const format = FORMAT[metric.key];
  const shape = trend(metric.series);
  const colors = metric.direction === "down" ? DOWN : UP;
  const aria =
    metric.direction === null
      ? `${title} за неделю`
      : `${title} за неделю: ${metric.direction === "up" ? "растёт" : "снижается"}`;

  return (
    <article className={styles.card}>
      <div className={styles.cardHead}>
        <div className={styles.cardTitles}>
          <h3 className={styles.cardTitle}>{title}</h3>
          <span className={styles.cardSub}>7 дней · {whoLabel}</span>
        </div>
        <div className={styles.cardTools}>
          <Pill pill={metric.pill} />
          <OpenLink href={HREFS[metric.key]} label={`Открыть: ${title}`} />
        </div>
      </div>
      <div className={styles.cardBody}>
        {metric.value === null ? (
          <div className={styles.noData}>{NO_DATA}</div>
        ) : (
          <>
            <div className={styles.metricValueRow}>
              <span className={styles.metricValue}>{format(metric.value)}</span>
              {metric.direction !== null && metric.delta !== null ? (
                <span className={`${styles.metricDelta} ${metric.direction === "up" ? styles.up : styles.down}`}>
                  {arrow(metric.direction)} {format(Math.abs(metric.delta))} к прошлой неделе
                </span>
              ) : (
                <span className={styles.metricDeltaMuted}>{NO_DATA} за прошлую неделю</span>
              )}
            </div>
            <ChartHover points={trendPoints(days, metric.series, today, title, format, colors.stroke)} label={title}>
              <svg viewBox="0 0 600 140" className={styles.trend} role="img" aria-label={aria}>
                {shape.area !== "" && <path d={shape.area} fill={colors.fill} className={styles.trendArea} />}
                <path d={shape.line} pathLength={1} fill="none" stroke={colors.stroke} strokeWidth="3" strokeLinejoin="round" strokeLinecap="round" className={styles.trendLine} />
                <path d={shape.dots} fill={colors.stroke} className={styles.trendDots} />
              </svg>
            </ChartHover>
          </>
        )}
      </div>
    </article>
  );
}
