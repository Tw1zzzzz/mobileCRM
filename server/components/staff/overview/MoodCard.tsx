import { donutArcs } from "@/lib/staff/overview-charts";
import { NO_DATA, records } from "@/lib/staff/overview-format";
import type { OverviewData } from "@/lib/staff/metrics";
import { MoodBreakdown } from "./MoodBreakdown";
import { OpenLink } from "./OpenLink";
import { Pill } from "./Pill";
import styles from "./overview.module.css";

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
        <div className={styles.cardTools}>
          <Pill pill={mood.pill} />
          <OpenLink href="/staff/statistics" label="Открыть настроение подробно" />
        </div>
      </div>
      {mood.average === null ? (
        <div className={styles.cardBody}>
          <div className={styles.noData}>{NO_DATA}</div>
        </div>
      ) : (
        <MoodBreakdown
          categories={mood.categories}
          arcs={arcs}
          average={mood.average}
          aria={aria}
          averageLabel={isTeam ? "среднее по команде" : "среднее"}
        />
      )}
    </article>
  );
}
