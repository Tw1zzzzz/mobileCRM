import type { OverviewData } from "@/lib/staff/metrics";
import { TEAM, type OverviewFilters, type RosterEntry } from "@/lib/staff/overview";
import { MetricChips, WhoChips } from "./FilterBar";
import { MetricCard } from "./MetricCard";
import { MoodCard } from "./MoodCard";
import { SleepEnergyCard } from "./SleepEnergyCard";
import styles from "./overview.module.css";

interface OverviewViewProps {
  overview: OverviewData;
  players: readonly RosterEntry[];
  filters: OverviewFilters;
}

/** The «Недельный обзор» block: filters, then the cards the selected metrics switch on. */
export function OverviewView({ overview, players, filters }: OverviewViewProps) {
  const isTeam = filters.who === TEAM;
  const whoLabel = isTeam ? "вся команда" : (players.find((p) => p.id === filters.who)?.nickname ?? "вся команда");
  const showState = filters.show.includes("state");
  const showSleep = filters.show.includes("sleep");
  const showFood = filters.show.includes("food");

  const metrics = overview.metrics.filter((m) =>
    m.key === "nutrition" ? showFood : showState,
  );

  return (
    <section className={styles.panel}>
      <div className={styles.panelHead}>
        <h2 className={styles.panelTitle}>Недельный обзор</h2>
        <MetricChips filters={filters} />
      </div>
      <WhoChips filters={filters} players={players} />

      {filters.show.length === 0 && <div className={styles.noData}>Выберите хотя бы одну метрику</div>}

      {(showState || showSleep) && (
        <div className={styles.grid}>
          {showState && <MoodCard mood={overview.mood} whoLabel={whoLabel} isTeam={isTeam} />}
          {showSleep && <SleepEnergyCard overview={overview} whoLabel={whoLabel} />}
        </div>
      )}

      {metrics.length > 0 && (
        <div className={styles.grid}>
          {metrics.map((m) => (
            <MetricCard key={m.key} metric={m} whoLabel={whoLabel} />
          ))}
        </div>
      )}
    </section>
  );
}
