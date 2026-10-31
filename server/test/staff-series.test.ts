import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { dailyInputs, readiness, shiftDay, type Documents } from "@/lib/staff/metrics";
import { periodDays, playerSeries, type PlayerSeries } from "@/lib/staff/series";

interface SeriesCase {
  name: string;
  docs: Documents;
  from: string;
  to: string;
  tz: string;
  expected: PlayerSeries;
}
interface PeriodCase {
  name: string;
  to: string;
  days: number;
  expected: { from: string; to: string; days: string[] };
}
interface Fixture {
  series: SeriesCase[];
  periodDays: PeriodCase[];
}

const fixture: Fixture = JSON.parse(
  readFileSync(fileURLToPath(new URL("./fixtures/staff-series.json", import.meta.url)), "utf8"),
);

const MOSCOW = "Europe/Moscow";

function named(name: string): SeriesCase {
  const found = fixture.series.find((c) => c.name === name);
  if (!found) throw new Error(`No fixture case ${name}`);
  return found;
}

function run(c: SeriesCase): PlayerSeries {
  return playerSeries({ id: "p", docs: c.docs }, c.from, c.to, c.tz);
}

const EMPTY: PlayerSeries = {
  sleep: {}, mood: {}, energy: {}, cog: {}, kcal: {}, screen: {}, balance: {}, focus: {}, fatigue: {}, elo: {}, readiness: {},
};

describe("fixture size", () => {
  it("has at least 12 cases", () => {
    expect(fixture.series.length + fixture.periodDays.length).toBeGreaterThanOrEqual(12);
  });
});

describe("playerSeries", () => {
  it("sleepNightEndsOnTheDay", () => {
    // 23:00 -> 07:30 and a 90-minute nap on the 15th add up; the night 22:00 -> 05:00 belongs to the 17th.
    expect(run(named("sleepNightEndsOnTheDay")).sleep).toEqual({ "2026-10-15": 10, "2026-10-17": 7 });
  });

  it("shortSleepIgnored", () => {
    // 59 minutes is not sleep, exactly 60 minutes is.
    expect(run(named("shortSleepIgnored")).sleep).toEqual({ "2026-10-16": 1 });
  });

  it("moodUsesPhoneDay", () => {
    const s = run(named("moodUsesPhoneDay"));
    // Yekaterinburg midnight 30 Oct (2026-10-29T19:00Z) and Moscow midnight 30 Oct are the same phone day.
    expect(s.mood).toEqual({ "2026-10-30": 7.5, "2026-10-31": 6.5 });
    expect(s.energy).toEqual({ "2026-10-30": 5.5, "2026-10-31": 5.5 });
  });

  it("screenHoursFromMinutes", () => {
    const s = run(named("screenHoursFromMinutes"));
    expect(s.screen["2026-10-29"]).toBe(0);
    expect(s.screen["2026-10-30"]).toBe(2); // two entries on the day: the last wins
    expect(s.screen["2026-10-31"]).toBeCloseTo(200 / 60, 12);
    expect(Object.keys(s.screen)).toEqual(["2026-10-29", "2026-10-30", "2026-10-31"]); // bad entries skipped
  });

  it("balanceMeanOfEightMissingKeyIsFive", () => {
    const s = run(named("balanceMeanOfEightMissingKeyIsFive"));
    expect(s.balance["2026-10-30"]).toBe(6.75); // the last entry of the day wins (46 / 8 would be 5.75)
    expect(s.balance["2026-10-31"]).toBe(5.625); // (10 + 7 * 5) / 8
    expect(s.balance["2026-11-01"]).toBe(5); // 11 and 0 are outside 1..10: the key counts as missing
    expect(s.balance["2026-11-02"]).toBe(5); // an empty wheel is all fives
    expect(Object.keys(s.balance)).toHaveLength(4); // an entry without `values` is skipped
  });

  it("eloLastSnapshotOfTheDay", () => {
    // Snapshots are not sorted in the file; the latest instant of the Moscow day wins.
    expect(run(named("eloLastSnapshotOfTheDay")).elo).toEqual({ "2026-10-15": 2025, "2026-10-16": 2040, "2026-10-17": 1990 });
  });

  it("focusFatigueDayMean", () => {
    const s = run(named("focusFatigueDayMean"));
    expect(s.focus["2026-10-15"]).toBe(7);
    expect(s.focus["2026-10-16"]).toBeCloseTo(17 / 3, 12);
    expect(s.fatigue["2026-10-15"]).toBe(5);
    expect(s.fatigue["2026-10-16"]).toBeCloseTo(13 / 3, 12);
    expect(s.focus["2026-10-17"]).toBeUndefined(); // focus 11 is out of range ...
    expect(s.fatigue["2026-10-17"]).toBe(6); // ... but the same entry's fatigue still counts
  });

  it("readinessSeriesMatchesDailyInputs", () => {
    const c = named("readinessSeriesMatchesDailyInputs");
    const s = run(c);
    const expected: Record<string, number> = {};
    for (let day = c.from; day <= c.to; day = shiftDay(day, 1)) {
      const value = readiness(dailyInputs(c.docs, day, c.tz));
      if (value !== null) expected[day] = value;
    }
    expect(s.readiness).toEqual(expected);
    expect(s.readiness).toEqual({ "2026-10-15": 83, "2026-10-16": 72, "2026-10-18": 58 });
  });

  it("gapsAreAbsentNotZero", () => {
    const s = run(named("gapsAreAbsentNotZero"));
    expect(Object.keys(s.mood)).toEqual(["2026-10-12", "2026-10-16"]);
    expect("2026-10-13" in s.mood).toBe(false);
    expect(s.kcal).toEqual({ "2026-10-16": 0 }); // a logged 0 is data, a missing day is not
  });

  it("rangeIsInclusive", () => {
    expect(run(named("rangeIsInclusiveAndClipped")).mood).toEqual({ "2026-10-15": 2, "2026-10-17": 3 });
  });

  it("emptyAndMalformedDocumentsGiveEmptySeries", () => {
    expect(run(named("emptyDocumentsGiveEmptySeries"))).toEqual(EMPTY);
    expect(run(named("malformedDocumentsGiveNothing"))).toEqual(EMPTY);
    expect(run(named("fromAfterToIsEmpty"))).toEqual(EMPTY);
  });

  it("realInstantsFollowTheStaffZone", () => {
    const utc = run(named("timeZoneMovesRealInstantsUtc"));
    const moscow = run(named("timeZoneMovesRealInstantsMoscow"));
    expect(utc.kcal).toEqual({ "2026-10-15": 500 });
    expect(moscow.kcal).toEqual({ "2026-10-16": 500 });
    expect(utc.elo).toEqual({ "2026-10-15": 2100 });
    expect(moscow.elo).toEqual({ "2026-10-16": 2100 });
    expect(utc.sleep).toEqual(moscow.sleep);
  });

  it("playerSeriesFixtureCases", () => {
    for (const c of fixture.series) {
      const actual = run(c);
      for (const key of Object.keys(c.expected) as (keyof PlayerSeries)[]) {
        const want = c.expected[key];
        const got = actual[key];
        expect(Object.keys(got), `${c.name}.${key} days`).toEqual(Object.keys(want));
        for (const day of Object.keys(want)) expect(got[day], `${c.name}.${key}.${day}`).toBeCloseTo(want[day], 9);
      }
      expect(Object.keys(actual).sort(), c.name).toEqual(Object.keys(c.expected).sort());
    }
  });

  it("rejectsAMalformedDay", () => {
    expect(() => playerSeries({ id: "p", docs: {} }, "15.10.2026", "2026-10-16", MOSCOW)).toThrow();
    expect(() => playerSeries({ id: "p", docs: {} }, "2026-10-15", "later", MOSCOW)).toThrow();
  });
});

describe("periodDays", () => {
  it("periodDaysEndsToday", () => {
    expect(periodDays("2026-10-31", 7)).toEqual({
      from: "2026-10-25",
      to: "2026-10-31",
      days: ["2026-10-25", "2026-10-26", "2026-10-27", "2026-10-28", "2026-10-29", "2026-10-30", "2026-10-31"],
    });
  });

  it("periodDaysFixtureCases", () => {
    for (const c of fixture.periodDays) expect(periodDays(c.to, c.days), c.name).toEqual(c.expected);
  });

  it("periodDaysNeedsAWholePositiveCount", () => {
    expect(() => periodDays("2026-10-31", 0)).toThrow();
    expect(() => periodDays("2026-10-31", 1.5)).toThrow();
  });
});
