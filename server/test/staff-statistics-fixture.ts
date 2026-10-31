import type { TestDeps } from "./helpers";
import { putDoc } from "./staff-card-fixture";

/**
 * Helpers for the «Статистика» tests: per-day synced state, a fake Faceit, and an independent Pearson (a different
 * formula from the one in `lib/staff/statistics.ts`) to compute the expected r by hand.
 */

export const TZ = "Europe/Moscow";
export const KEY = "test-faceit-key";

export const FACEIT_IDS = {
  vega: "aaaaaaaa-0000-4000-8000-00000000000a",
  nova: "aaaaaaaa-0000-4000-8000-00000000000b",
  ghost: "aaaaaaaa-0000-4000-8000-00000000000c",
  outsider: "aaaaaaaa-0000-4000-8000-0000000000ff",
};

export interface DayRow {
  day: string;
  sleep?: number;
  mood?: number;
  energy?: number;
  screen?: number;
  elo?: number;
}

/** Writes sleep, wellbeing, screen-time and faceit snapshots so that each row's numbers are the day's series. */
export async function seedState(deps: TestDeps, userId: string, rows: DayRow[], faceit?: { playerId: string | null }): Promise<void> {
  const sessions = rows
    .filter((r) => r.sleep !== undefined)
    .map((r) => {
      const end = Date.parse(`${r.day}T04:00:00Z`); // 07:00 in Moscow: the night belongs to `day`
      return { id: `s-${r.day}`, start: new Date(end - (r.sleep as number) * 3_600_000).toISOString(), end: new Date(end).toISOString() };
    });
  const mood = rows
    .filter((r) => r.mood !== undefined)
    .map((r) => ({ id: `m-${r.day}`, day: `${r.day}T08:00:00Z`, mood: r.mood, energy: r.energy ?? r.mood }));
  const entries = rows
    .filter((r) => r.screen !== undefined)
    .map((r) => ({ id: `t-${r.day}`, date: `${r.day}T00:00:00Z`, minutes: (r.screen as number) * 60 }));
  const snapshots = rows.filter((r) => r.elo !== undefined).map((r) => ({ date: `${r.day}T09:00:00Z`, elo: r.elo }));
  await putDoc(deps, userId, "sleep", { sessions });
  await putDoc(deps, userId, "wellbeing", { mood });
  await putDoc(deps, userId, "screen-time", { entries });
  if (faceit) await putDoc(deps, userId, "faceit", { nickname: "nick", playerId: faceit.playerId, snapshots });
}

/** `count` days ending at `end` (a `YYYY-MM-DD`), oldest first. */
export function daysBefore(end: string, count: number): string[] {
  const last = Date.parse(`${end}T00:00:00Z`);
  return Array.from({ length: count }, (_, i) => new Date(last - (count - 1 - i) * 86_400_000).toISOString().slice(0, 10));
}

const ms = (iso: string): number => Date.parse(iso);

/** One raw Faceit item. */
export function item(finishedAt: string, stats: Record<string, unknown> = {}): unknown {
  return {
    stats: {
      Result: "1", Score: "13 / 9", Kills: "20", Deaths: "10", Assists: "4", ADR: "80", Rounds: "22",
      "Match Finished At": ms(finishedAt), ...stats,
    },
  };
}

export interface FakeFaceit {
  http: typeof fetch;
  /** Every requested URL. */
  calls: string[];
  /** Most requests that were waiting for an answer at the same moment. */
  maxInFlight: () => number;
}

/** Answers `GET .../players/{id}/games/cs2/stats` by player id: an array of items, or an HTTP status number. */
export function fakeFaceit(byPlayer: Record<string, unknown[] | number>, delayMs = 0): FakeFaceit {
  const calls: string[] = [];
  let inFlight = 0;
  let peak = 0;
  const http = (async (input: unknown) => {
    calls.push(String(input));
    inFlight += 1;
    peak = Math.max(peak, inFlight);
    if (delayMs > 0) await new Promise((resolve) => setTimeout(resolve, delayMs));
    inFlight -= 1;
    const id = String(input).match(/\/players\/([^/]+)\/games\/cs2\/stats/)?.[1];
    const answer = id === undefined ? undefined : byPlayer[id];
    if (answer === undefined) return new Response("{}", { status: 404 });
    if (typeof answer === "number") return new Response("{}", { status: answer });
    return new Response(JSON.stringify({ items: answer }), { status: 200, headers: { "content-type": "application/json" } });
  }) as typeof fetch;
  return { http, calls, maxInFlight: () => peak };
}

/** A match per row: `kills` over 10 deaths, finished at 13:00 Moscow on the row's day. */
export function matchesFor(rows: { day: string; kills: number; adr?: number; won?: boolean }[]): unknown[] {
  return rows.map((r) =>
    item(`${r.day}T10:00:00Z`, {
      Kills: String(r.kills), Deaths: "10", ADR: String(r.adr ?? 80), Result: r.won === false ? "0" : "1",
    }),
  );
}

/** Pearson by the textbook sum formula (the app code uses mean-centred sums). */
export function pearsonRef(xs: number[], ys: number[]): number {
  const n = xs.length;
  const sum = (v: number[]): number => v.reduce((a, b) => a + b, 0);
  const sxy = sum(xs.map((x, i) => x * ys[i]));
  const num = n * sxy - sum(xs) * sum(ys);
  const den = Math.sqrt((n * sum(xs.map((x) => x * x)) - sum(xs) ** 2) * (n * sum(ys.map((y) => y * y)) - sum(ys) ** 2));
  return num / den;
}

/** The page's «+0.00» / «−0.00». */
export function signed(r: number): string {
  return `${r >= 0 ? "+" : "−"}${Math.abs(r).toFixed(2)}`;
}
