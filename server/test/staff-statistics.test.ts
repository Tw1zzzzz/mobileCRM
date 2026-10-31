import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import type { DaySeries } from "@/lib/staff/series";
import {
  correlate,
  correlatePooled,
  keyLinks,
  medianSplit,
  pairByDay,
  pearson,
  STAT_METRICS,
  strength,
  type KeyLink,
  type LinkMetric,
  type MedianSplit,
} from "@/lib/staff/statistics";

interface Fixture {
  pearson: { name: string; xs: number[]; ys: number[]; expected: number | null }[];
  pairByDay: { name: string; a: DaySeries; b: DaySeries; expected: { xs: number[]; ys: number[] } }[];
  strength: { r: number | null; label: string; direction: "direct" | "inverse" | null }[];
  correlate: { name: string; a: DaySeries; b: DaySeries; expected: number | null }[];
  pooled: { name: string; pairs: { a: DaySeries; b: DaySeries }[]; expected: number | null }[];
  medianSplit: { name: string; xs: number[]; ys: number[]; expected: MedianSplit | null }[];
  keyLinks: {
    name: string;
    metrics: LinkMetric[];
    matrix: Record<string, Record<string, number | null>>;
    expected: KeyLink[];
  }[];
}

const fixture: Fixture = JSON.parse(
  readFileSync(fileURLToPath(new URL("./fixtures/staff-statistics.json", import.meta.url)), "utf8"),
);

function named<T extends { name: string }>(list: T[], name: string): T {
  const found = list.find((c) => c.name === name);
  if (!found) throw new Error(`No fixture case ${name}`);
  return found;
}

function expectClose(actual: number | null, expected: number | null, label: string): void {
  if (expected === null) expect(actual, label).toBeNull();
  else {
    expect(actual, label).not.toBeNull();
    expect(Math.abs((actual as number) - expected), label).toBeLessThan(1e-9);
  }
}

describe("pearson", () => {
  it("fixture cases", () => {
    for (const c of fixture.pearson) expectClose(pearson(c.xs, c.ys), c.expected, c.name);
  });
  it("pearsonPerfect", () => {
    expect(pearson([1, 2, 3, 4, 5], [3, 5, 7, 9, 11])).toBeCloseTo(1, 12);
    expect(pearson([1, 2, 3, 4], [8, 6, 4, 2])).toBeCloseTo(-1, 12);
  });
  it("pearsonTooFewPairsIsNull", () => {
    expect(pearson([1, 2], [3, 4])).toBeNull();
    expect(pearson([], [])).toBeNull();
    expect(pearson([1, 2, 3, 4], [1, 2, 3])).toBeNull();
  });
  it("pearsonZeroVarianceIsNull", () => {
    expect(pearson([5, 5, 5, 5], [1, 2, 3, 4])).toBeNull();
    expect(pearson([1, 2, 3, 4], [7, 7, 7, 7])).toBeNull();
  });
  it("is not rounded", () => {
    const r = pearson([6.5, 7, 7.5, 8, 5.5, 9], [41, 52, 47, 66, 38, 58]) as number;
    expect(r).not.toBe(Math.round(r * 100) / 100);
  });
});

describe("pairByDay", () => {
  it("fixture cases", () => {
    for (const c of fixture.pairByDay) expect(pairByDay(c.a, c.b), c.name).toEqual(c.expected);
  });
  it("pairByDayDropsGaps", () => {
    const c = named(fixture.pairByDay, "pairByDayDropsGaps");
    expect(pairByDay(c.a, c.b)).toEqual({ xs: [6, 8, 5], ys: [50, 70, 40] });
  });
  it("does not mutate its inputs", () => {
    const a = { "2026-10-21": 1 };
    const b = { "2026-10-21": 2 };
    pairByDay(a, b);
    expect(a).toEqual({ "2026-10-21": 1 });
    expect(b).toEqual({ "2026-10-21": 2 });
  });
});

describe("strength", () => {
  it("strengthLabelsEdges", () => {
    for (const c of fixture.strength) {
      expect(strength(c.r), String(c.r)).toEqual({ label: c.label, direction: c.direction });
    }
  });
  it("uses the board labels, not the app's 0.6 / 0.3", () => {
    expect(strength(0.7).label).toBe("Сильная");
    expect(strength(0.65).label).toBe("Заметная");
    expect(strength(0.4).label).toBe("Заметная");
    expect(strength(0.2).label).toBe("Слабая");
    expect(strength(0.19).label).toBe("Связи почти нет");
    expect(strength(-0.7)).toEqual({ label: "Сильная", direction: "inverse" });
    expect(strength(0.19).direction).toBeNull();
    expect(strength(null)).toEqual({ label: "мало данных", direction: null });
  });
});

describe("correlate", () => {
  it("fixture cases", () => {
    for (const c of fixture.correlate) expectClose(correlate(c.a, c.b), c.expected, c.name);
  });
  it("two shared days is not enough", () => {
    const c = named(fixture.correlate, "correlateTwoSharedDaysIsNull");
    expect(correlate(c.a, c.b)).toBeNull();
  });
});

describe("correlatePooled", () => {
  it("fixture cases", () => {
    for (const c of fixture.pooled) expectClose(correlatePooled(c.pairs), c.expected, c.name);
  });
  it("pooledUsesAllPlayers", () => {
    const c = named(fixture.pooled, "pooledUsesAllPlayers");
    expect(correlate(c.pairs[0].a, c.pairs[0].b)).toBeNull();
    expect(correlate(c.pairs[1].a, c.pairs[1].b)).toBeNull();
    expect(correlatePooled(c.pairs)).not.toBeNull();
  });
  it("a single player pools to the same r as correlate", () => {
    const c = named(fixture.pooled, "pooledSinglePlayerEqualsCorrelate");
    expect(correlatePooled(c.pairs)).toBe(correlate(c.pairs[0].a, c.pairs[0].b));
  });
});

describe("medianSplit", () => {
  it("fixture cases", () => {
    for (const c of fixture.medianSplit) {
      const actual = medianSplit(c.xs, c.ys);
      if (c.expected === null) expect(actual, c.name).toBeNull();
      else {
        expect(actual, c.name).not.toBeNull();
        expect(actual?.median, c.name).toBe(c.expected.median);
        expectClose(actual?.hiMean ?? null, c.expected.hiMean, `${c.name} hi`);
        expectClose(actual?.loMean ?? null, c.expected.loMean, `${c.name} lo`);
      }
    }
  });
  it("medianSplitMatchesBoard", () => {
    // sorted xs = 5 6 7 7.5 8, median = sorted[2] = 7; hi = xs >= 7 -> ys 60, 70, 50; lo -> 40, 35
    const r = medianSplit([6, 7.5, 5, 8, 7], [40, 60, 35, 70, 50]);
    expect(r?.median).toBe(7);
    expect(r?.hiMean).toBeCloseTo(60, 12);
    expect(r?.loMean).toBeCloseTo(37.5, 12);
  });
  it("an even count takes the upper middle value", () => {
    expect(medianSplit([1, 2, 3, 4], [10, 20, 30, 50])?.median).toBe(3);
  });
});

describe("keyLinks", () => {
  it("fixture cases", () => {
    for (const c of fixture.keyLinks) {
      const actual = keyLinks(c.metrics, c.matrix);
      expect(actual.map((l) => [l.a, l.b, l.cross]), c.name).toEqual(c.expected.map((l) => [l.a, l.b, l.cross]));
      actual.forEach((l, i) => expectClose(l.r, c.expected[i].r, `${c.name}[${i}]`));
    }
  });
  it("keyLinksPrefersCrossPairs", () => {
    const c = named(fixture.keyLinks, "keyLinksPrefersCrossPairs");
    const links = keyLinks(c.metrics, c.matrix);
    // mood-sleep (0.95) and elo-kd (0.99) are stronger but not cross
    expect(links.map((l) => `${l.a}-${l.b}`)).toEqual(["sleep-elo", "mood-kd", "mood-elo"]);
    expect(links.every((l) => l.cross)).toBe(true);
  });
  it("keyLinksSwapsGameFirst", () => {
    const c = named(fixture.keyLinks, "keyLinksSwapsGameFirst");
    const links = keyLinks(c.metrics, c.matrix);
    expect(links[0]).toEqual({ a: "sleep", b: "kd", r: -0.8, cross: true });
    const group = new Map(c.metrics.map((m) => [m.id, m.group]));
    for (const l of links) expect(group.get(l.a)).toBe("state");
  });
  it("falls back to all pairs when no cross pair has an r", () => {
    const c = named(fixture.keyLinks, "keyLinksFallsBackWhenEveryCrossPairIsNull");
    expect(keyLinks(c.metrics, c.matrix).map((l) => [l.a, l.b, l.cross])).toEqual([
      ["elo", "kd", false],
      ["mood", "sleep", false],
    ]);
  });
  it("null pairs are never links", () => {
    const c = named(fixture.keyLinks, "keyLinksNullPairsNeverLink");
    expect(keyLinks(c.metrics, c.matrix)).toEqual([{ a: "mood", b: "kd", r: 0.3, cross: true }]);
    const none = named(fixture.keyLinks, "keyLinksAllNullIsEmpty");
    expect(keyLinks(none.metrics, none.matrix)).toEqual([]);
  });
  it("ties keep input order", () => {
    const c = named(fixture.keyLinks, "keyLinksTiesKeepInputOrder");
    expect(keyLinks(c.metrics, c.matrix).map((l) => `${l.a}-${l.b}`)).toEqual([
      "mood-elo",
      "mood-kd",
      "sleep-elo",
    ]);
  });
  it("reads either triangle of the matrix", () => {
    const c = named(fixture.keyLinks, "keyLinksMatrixMayBeFilledOnTheOtherTriangle");
    expect(keyLinks(c.metrics, c.matrix).map((l) => [l.a, l.b, l.r])).toEqual([
      ["mood", "kd", -0.7],
      ["mood", "elo", 0.6],
    ]);
  });
});

describe("STAT_METRICS", () => {
  it("has the board's eleven metrics without KAST", () => {
    expect(STAT_METRICS.map((m) => m.id)).toEqual([
      "mood", "energy", "sleep", "screen", "cog", "balance", "kcal", "elo", "kd", "adr", "wr",
    ]);
  });
  it("keeps the board's labels verbatim", () => {
    const label = (id: string) => STAT_METRICS.find((m) => m.id === id);
    expect(label("mood")).toMatchObject({ label: "Настроение", lower: "настроение", group: "state", unit: "" });
    expect(label("energy")).toMatchObject({ label: "Энергия", lower: "энергия" });
    expect(label("sleep")).toMatchObject({ label: "Сон", lower: "сон", unit: "ч" });
    expect(label("screen")).toMatchObject({ label: "Экранное время", lower: "экранное время", unit: "ч" });
    expect(label("cog")).toMatchObject({ label: "Индекс когн. формы", lower: "индекс когнитивной формы" });
    expect(label("balance")).toMatchObject({ label: "Баланс жизни", lower: "баланс жизни" });
    expect(label("kcal")).toMatchObject({ label: "Калорийность", lower: "калорийность", unit: "ккал" });
    expect(label("elo")).toMatchObject({ label: "Текущий ELO", lower: "ELO", group: "game" });
    expect(label("kd")).toMatchObject({ label: "K/D", lower: "K/D", group: "game" });
    expect(label("adr")).toMatchObject({ label: "ADR", lower: "ADR", group: "game" });
    expect(label("wr")).toMatchObject({ label: "Win Rate", lower: "win rate", group: "game", unit: "%" });
  });
  it("splits 7 state metrics from 4 game metrics", () => {
    expect(STAT_METRICS.filter((m) => m.group === "state")).toHaveLength(7);
    expect(STAT_METRICS.filter((m) => m.group === "game")).toHaveLength(4);
  });
});
