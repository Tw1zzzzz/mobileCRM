import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import {
  PILL_THRESHOLDS,
  STAFF_TIME_ZONE,
  dailyInputs,
  moodCategories,
  pill,
  readiness,
  readinessStatus,
  teamOverview,
  type DailyInputs,
  type Documents,
  type PlayerDocuments,
} from "@/lib/staff/metrics";

interface Named {
  name: string;
}
interface Fixture {
  thresholds: Record<string, { ok: number; mid: number }>;
  readiness: (Named & { inputs: DailyInputs; expected: number | null })[];
  readinessStatus: (Named & { value: number; expected: string })[];
  moodCategories: (Named & { entries: number[]; expected: unknown })[];
  pill: (Named & { value: number; ok: number; mid: number; expected: string })[];
  dailyInputs: (Named & { docs: Documents; day: string; tz: string; expected: DailyInputs })[];
  rosters: Record<string, PlayerDocuments[]>;
  teamOverview: (Named & { roster: string; ids: string[] | null; today: string; tz: string; who: string; expected: unknown })[];
}

const fixture: Fixture = JSON.parse(
  readFileSync(fileURLToPath(new URL("./fixtures/staff-metrics.json", import.meta.url)), "utf8"),
);

/** What the page serialises: `undefined` keys vanish, exactly as in the Kotlin comparison. */
const wire = (value: unknown): unknown => JSON.parse(JSON.stringify(value));

describe("fixture size", () => {
  it("has at least 12 cases", () => {
    const total =
      fixture.readiness.length + fixture.readinessStatus.length + fixture.moodCategories.length +
      fixture.pill.length + fixture.dailyInputs.length + fixture.teamOverview.length;
    expect(total).toBeGreaterThanOrEqual(12);
  });
});

describe("readiness", () => {
  it("readinessMatchesDesignFormula", () => {
    // (8 - 4.5) / 4 * 30 + 7 * 3 + 6 * 2.5 + 70 * 0.3 = 26.25 + 21 + 15 + 21 = 83.25
    expect(readiness({ sleepHours: 8, mood: 7, energy: 6, cog: 70 })).toBe(83);
    expect(readiness({ sleepHours: 6, mood: 5, energy: 5, cog: 50 })).toBe(54);
    // half rounds up (a half-even round would give 42)
    expect(readiness({ sleepHours: 6.5, mood: 5, energy: 5, cog: 0 })).toBe(43);
  });

  it("readinessFromSleepOnly", () => {
    // 18.75 * 115 / 30 = 71.875
    expect(readiness({ sleepHours: 7 })).toBe(72);
    expect(readiness({ sleepHours: 7, kcal: 2000 })).toBe(72);
  });

  it("readinessNothingIsNull", () => {
    expect(readiness({})).toBeNull();
    expect(readiness({ kcal: 2000 })).toBeNull();
  });

  it("readinessClamps", () => {
    expect(readiness({ sleepHours: 12, mood: 10, energy: 10, cog: 100 })).toBe(100);
    expect(readiness({ sleepHours: 3, mood: 1, energy: 1, cog: 0 })).toBe(0);
  });

  it("readinessMissingInputsScaleBySumOfMaxima", () => {
    expect(readiness({ sleepHours: 8, mood: 7, energy: 6 })).toBe(84); // 62.25 * 115 / 85
    expect(readiness({ sleepHours: 8, mood: 7, cog: 70 })).toBe(87); // 68.25 * 115 / 90
  });

  it("readinessFixtureCases", () => {
    for (const c of fixture.readiness) expect(readiness(c.inputs), c.name).toBe(c.expected);
  });

  it("readinessStatusBoundaries", () => {
    expect(readinessStatus(70)).toBe("ready");
    expect(readinessStatus(69)).toBe("doubt");
    expect(readinessStatus(55)).toBe("doubt");
    expect(readinessStatus(54)).toBe("risk");
    for (const c of fixture.readinessStatus) expect(readinessStatus(c.value), c.name).toBe(c.expected);
  });
});

describe("mood categories and pills", () => {
  it("moodCategoriesFromBoard", () => {
    const names = moodCategories([]).map((c) => [c.name, c.range]);
    expect(names).toEqual([
      ["Отличное", "8–10"],
      ["Хорошее", "6–7"],
      ["Среднее", "4–5"],
      ["Плохое", "1–3"],
    ]);
    expect(moodCategories([7.5, 5.5, 3.5, 3.49]).map((c) => c.count)).toEqual([1, 1, 1, 1]);
    for (const c of fixture.moodCategories) expect(moodCategories(c.entries), c.name).toEqual(c.expected);
  });

  it("pillThresholdsFromBoard", () => {
    expect(PILL_THRESHOLDS).toEqual({
      mood: { ok: 6.8, mid: 5.2 },
      sleep: { ok: 7.3, mid: 6.6 },
      readiness: { ok: 70, mid: 55 },
      cognitive: { ok: 66, mid: 56 },
      nutrition: { ok: 2100, mid: 1700 },
      kd: { ok: 1.05, mid: 0.95 },
      winRate: { ok: 55, mid: 45 },
    });
    expect(PILL_THRESHOLDS).toEqual(fixture.thresholds);
    for (const c of fixture.pill) expect(pill(c.value, c.ok, c.mid), c.name).toBe(c.expected);
  });
});

describe("dailyInputs", () => {
  it("dailyInputsUsesStaffTimeZone", () => {
    const docs = {
      sleep: { sessions: [{ id: "a", start: "2026-10-14T16:00:00Z", end: "2026-10-14T23:30:00Z" }] },
    };
    // 23:30 UTC is 02:30 on the 15th in Moscow
    expect(dailyInputs(docs, "2026-10-15", "Europe/Moscow")).toEqual({ sleepHours: 7.5 });
    expect(dailyInputs(docs, "2026-10-14", "Europe/Moscow")).toEqual({});
    expect(dailyInputs(docs, "2026-10-14", "UTC")).toEqual({ sleepHours: 7.5 });
  });

  it("staffTimeZoneDefaultsToMoscow", () => {
    expect(STAFF_TIME_ZONE).toBe(process.env.STAFF_TIME_ZONE || "Europe/Moscow");
  });

  it("dailyInputsFixtureCases", () => {
    for (const c of fixture.dailyInputs) expect(wire(dailyInputs(c.docs, c.day, c.tz)), c.name).toEqual(c.expected);
  });
});

describe("teamOverview", () => {
  const players = (c: Fixture["teamOverview"][number]): PlayerDocuments[] => {
    const roster = fixture.rosters[c.roster];
    return c.ids === null ? roster : c.ids.map((id) => roster.find((p) => p.id === id)!);
  };

  it("teamOverviewSkipsPlayersWithoutData", () => {
    const main = fixture.rosters.main;
    const withEmpty = teamOverview(main, "2026-10-15", "Europe/Moscow", "team");
    const without = teamOverview(main.filter((p) => p.id === "p1" || p.id === "p2"), "2026-10-15", "Europe/Moscow", "team");
    expect(withEmpty.teamReadiness).toBe(without.teamReadiness);
    expect(withEmpty.metrics).toEqual(without.metrics);
    expect(withEmpty.sleep).toEqual(without.sleep);
    expect(withEmpty.mood).toEqual(without.mood);
    expect(withEmpty.playerReadiness.find((p) => p.id === "p3")?.readiness).toBeNull();
  });

  it("teamOverviewEmptyTeamIsAllNoData", () => {
    const empty = teamOverview([], "2026-10-15", "Europe/Moscow", "team");
    expect(empty.teamReadiness).toBeNull();
    expect(empty.mood.average).toBeNull();
    expect(empty.sleep.lowest).toBeNull();
    expect(empty.metrics.every((m) => m.value === null && m.pill === null)).toBe(true);
  });

  it("teamOverviewWindowIsSevenDaysEndingToday", () => {
    const o = teamOverview([], "2026-10-15", "Europe/Moscow", "team");
    expect(o.days).toEqual([
      "2026-10-09", "2026-10-10", "2026-10-11", "2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15",
    ]);
  });

  it("teamOverviewFixtureCases", () => {
    for (const c of fixture.teamOverview) {
      expect(wire(teamOverview(players(c), c.today, c.tz, c.who)), c.name).toEqual(c.expected);
    }
  });
});
