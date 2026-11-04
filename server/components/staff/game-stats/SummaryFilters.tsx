import Link from "next/link";
import type { ReactNode } from "react";
import { GAME_STATS_PATH, SUMMARY_PRESETS, summaryHref, type KindFilter, type SummaryParams } from "@/lib/staff/game-summary";
import { TEAM } from "@/lib/staff/overview";
import type { RosterPlayer } from "./draft";
import styles from "./game-stats.module.css";
import { cx } from "./ui";

const SOURCES: readonly { kind: KindFilter; label: string }[] = [
  { kind: "all", label: "Все" },
  { kind: "pracc", label: "Пракки" },
  { kind: "tournament", label: "Турниры" },
];

/** A filter option as a link (the filters live in the URL): `kind` is the look, chip or segment of a group. */
function Pick({ href, on, kind, children }: { href: string; on: boolean; kind: "chip" | "seg"; children: ReactNode }) {
  const className = kind === "chip" ? cx(styles.chip, on && styles.chipOn) : cx(styles.segBtn, on && styles.segOn);
  return (
    <Link href={href} scroll={false} className={className} aria-current={on ? "true" : undefined}>
      {children}
    </Link>
  );
}

/** «Свой период»: a plain GET form with two dates (no script); the other filters ride along as hidden fields. */
function CustomDates({ params }: { params: SummaryParams }) {
  return (
    <form method="get" action={`${GAME_STATS_PATH}#gs-period`} className={styles.dates}>
      <input type="hidden" name="period" value="custom" />
      {params.kind !== "all" && <input type="hidden" name="kind" value={params.kind} />}
      {params.who !== TEAM && <input type="hidden" name="who" value={params.who} />}
      <label htmlFor="gs-from">с</label>
      <input id="gs-from" className={styles.dateInput} type="date" name="from" defaultValue={params.range.from} required />
      <label htmlFor="gs-to">по</label>
      <input id="gs-to" className={styles.dateInput} type="date" name="to" defaultValue={params.range.to} required />
      <button type="submit" className={styles.btnGhost}>Показать</button>
    </form>
  );
}

/** The period chips (the board's «7 дней» … «Свой период»). */
export function PeriodFilter({ params }: { params: SummaryParams }) {
  return (
    <>
      <div role="group" aria-label="Период" className={styles.chips}>
        {SUMMARY_PRESETS.map((days) => (
          <Pick key={days} kind="chip" href={summaryHref(params, { period: days })} on={params.period === days}>
            {`${days} дней`}
          </Pick>
        ))}
        <Pick kind="chip" href={summaryHref(params, { period: "custom" })} on={params.period === "custom"}>
          Свой период
        </Pick>
      </div>
      {params.period === "custom" && <CustomDates params={params} />}
    </>
  );
}

/** «Все / Пракки / Турниры» and one chip per player of the roster next to «Команда». */
export function SourceAndPlayerFilter({ params, roster }: { params: SummaryParams; roster: readonly RosterPlayer[] }) {
  return (
    <div className={styles.filterRow}>
      <div role="group" aria-label="Источник" className={styles.segwrap}>
        {SOURCES.map((source) => (
          <Pick key={source.kind} kind="seg" href={summaryHref(params, { kind: source.kind })} on={params.kind === source.kind}>
            {source.label}
          </Pick>
        ))}
      </div>
      <div role="group" aria-label="Игрок" className={styles.chips}>
        <Pick kind="chip" href={summaryHref(params, { who: TEAM })} on={params.who === TEAM}>
          Команда
        </Pick>
        {roster.map((player) => (
          <Pick key={player.id} kind="chip" href={summaryHref(params, { who: player.id })} on={params.who === player.id}>
            {player.nickname}
          </Pick>
        ))}
      </div>
    </div>
  );
}
