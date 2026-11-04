import { linesDaily, type GameLine } from "@/lib/staff/game-lines";
import { WINDOW_DAYS, daysEndingAt, mean, shiftDay, weeklyFromSeries, type Day, type WeeklyMetric } from "@/lib/staff/metrics";
import type { DaySeries } from "@/lib/staff/series";

/**
 * The «Игра» chip of «Обзор» (`TeamOverview.dc.html`): weekly K/D and Win-rate cards over the selected players'
 * Faceit matches and hand-entered (pracc / tournament) maps. Pure; the loader reads the lines.
 */

export interface PlayerLines {
  id: string;
  /** The player's lines of the last two weeks (`gameWindow`). */
  lines: readonly GameLine[];
}

/** The days the lines are read for: this week and the one before it, ending `today`. */
export function gameWindow(today: Day): { from: Day; to: Day } {
  return { from: shiftDay(today, -(2 * WINDOW_DAYS - 1)), to: today };
}

/** Per day, the mean over players of each player's own mean of that day; a day nobody played is `null`. */
function teamSeries(perPlayer: readonly DaySeries[], days: readonly Day[]): (number | null)[] {
  return days.map((day) => mean(perPlayer.flatMap((series) => (day in series ? [series[day]] : []))));
}

/** K/D and Win-rate for `who` (`"team"` or a roster id) over the week ending `today`, against the week before. */
export function gameMetrics(players: readonly PlayerLines[], who: string, today: Day): WeeklyMetric[] {
  const selected = who === "team" ? players : players.filter((p) => p.id === who);
  const daily = selected.map((p) => linesDaily(p.lines));
  const days = daysEndingAt(today, WINDOW_DAYS);
  const previousDays = daysEndingAt(shiftDay(today, -WINDOW_DAYS), WINDOW_DAYS);
  const metric = (key: "kd" | "winRate", pick: (d: ReturnType<typeof linesDaily>) => DaySeries): WeeklyMetric => {
    const perPlayer = daily.map(pick);
    return weeklyFromSeries(key, teamSeries(perPlayer, days), teamSeries(perPlayer, previousDays));
  };
  return [metric("kd", (d) => d.kd), metric("winRate", (d) => d.winRate)];
}
