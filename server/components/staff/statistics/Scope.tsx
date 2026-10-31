import Link from "next/link";
import { TEAM, type RosterEntry } from "@/lib/staff/overview";
import { STATS_PATH, STATS_PRESETS, DEFAULT_METRICS, statsHref, type StatsParams } from "@/lib/staff/statistics-page";
import styles from "./statistics.module.css";

function Chip({ href, on, children }: { href: string; on: boolean; children: React.ReactNode }) {
  return (
    <Link href={href} scroll={false} className={on ? `${styles.chip} ${styles.chipOn}` : styles.chip} aria-current={on ? "true" : undefined}>
      {children}
    </Link>
  );
}

/** «Вся команда» and one chip per player of the roster. */
export function WhoChips({ params, players }: { params: StatsParams; players: readonly RosterEntry[] }) {
  return (
    <div role="group" aria-label="Чьи данные" className={styles.chips}>
      <Chip href={statsHref(params, { who: TEAM })} on={params.who === TEAM}>
        Вся команда
      </Chip>
      {players.map((p) => (
        <Chip key={p.id} href={statsHref(params, { who: p.id })} on={params.who === p.id}>
          {p.nickname}
        </Chip>
      ))}
    </div>
  );
}

const PRESET_LABELS: Record<(typeof STATS_PRESETS)[number], string> = {
  7: "7 дней",
  30: "30 дней",
  90: "90 дней",
  365: "1 год",
};

/** The board's period chips; «Свой период» opens a plain GET form with two dates (no script). */
export function PeriodChips({ params }: { params: StatsParams }) {
  return (
    <>
      <div role="group" aria-label="Период анализа" className={styles.chips}>
        {STATS_PRESETS.map((days) => (
          <Chip key={days} href={statsHref(params, { period: days })} on={params.period === days}>
            {PRESET_LABELS[days]}
          </Chip>
        ))}
        <Chip href={statsHref(params, { period: "custom" })} on={params.period === "custom"}>
          Свой период
        </Chip>
      </div>
      {params.period === "custom" && <CustomDates params={params} />}
    </>
  );
}

function CustomDates({ params }: { params: StatsParams }) {
  const metrics = params.metrics.join(",");
  const defaults = params.metrics.join(",") === DEFAULT_METRICS.join(",");
  return (
    <form method="get" action={STATS_PATH} className={styles.dates}>
      {params.who !== TEAM && <input type="hidden" name="who" value={params.who} readOnly />}
      {!defaults && <input type="hidden" name="m" value={metrics} readOnly />}
      <input type="hidden" name="period" value="custom" readOnly />
      <label htmlFor="stats-from">с</label>
      <input id="stats-from" className={styles.date} type="date" name="from" defaultValue={params.range.from} required />
      <label htmlFor="stats-to">по</label>
      <input id="stats-to" className={styles.date} type="date" name="to" defaultValue={params.range.to} required />
      <button type="submit" className={styles.apply}>
        Показать
      </button>
    </form>
  );
}
