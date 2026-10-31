import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { removeMember } from "../lib/team/members";
import { requireStaff } from "../lib/staff/access";
import {
  CARD_COLLECTIONS,
  cardHref,
  loadCard,
  parseCardParams,
  type CardData,
  type CardParams,
} from "../lib/staff/card";
import { resetDb, testDeps } from "./helpers";
import { putDoc, seedCardTeam, type CardTeam } from "./staff-card-fixture";

const deps = testDeps();
const TZ = "Europe/Moscow";

beforeEach(async () => {
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

function cardOf(data: CardData) {
  if (data.kind !== "card") throw new Error("expected a card");
  return data;
}

const BASE: CardParams = { player: "p1", period: 30, tab: "profile", type: "all", src: "all", day: null };

describe("parseCardParams", () => {
  const ids = ["p1", "p2"];

  it("defaultsToTheFirstPlayerProfileThirtyDays", () => {
    expect(parseCardParams({}, ids)).toEqual(BASE);
  });

  it("takesWhatTheUrlSays", () => {
    expect(
      parseCardParams(
        { player: "p2", period: "90", tab: "sleep", type: "cog", src: "faceit", day: "2026-10-20" },
        ids,
      ),
    ).toEqual({ player: "p2", period: 90, tab: "sleep", type: "cog", src: "faceit", day: "2026-10-20" });
    expect(parseCardParams({ period: "7", tab: "tests", type: "game" }, ids)).toMatchObject({
      period: 7, tab: "tests", type: "game",
    });
  });

  it("everyInvalidValueFallsBackWithoutAnError", () => {
    expect(
      parseCardParams({ player: "zzz", period: "45", tab: "weekly", type: "week", src: "prak", day: "2026-13-45" }, ids),
    ).toEqual(BASE);
    expect(parseCardParams({ day: "yesterday" }, ids)?.day).toBeNull();
    expect(parseCardParams({ day: "2026-02-31" }, ids)?.day).toBeNull();
    expect(parseCardParams({ period: "" , player: ""}, ids)).toEqual(BASE);
  });

  it("firstValueOfARepeatedParameterWins", () => {
    expect(parseCardParams({ player: ["p2", "p1"], tab: ["sleep", "tests"] }, ids)).toMatchObject({
      player: "p2", tab: "sleep",
    });
    expect(parseCardParams({ player: ["zzz", "p2"] }, ids)?.player).toBe("p1");
  });

  it("noRosterMeansNoCard", () => {
    expect(parseCardParams({ player: "p1" }, [])).toBeNull();
  });
});

describe("cardHref", () => {
  it("leavesDefaultsOut", () => {
    expect(cardHref(BASE)).toBe("/staff/players?player=p1");
  });

  it("keepsEverythingElseWhenOneThingChanges", () => {
    const current: CardParams = { player: "p1", period: 7, tab: "tests", type: "cog", src: "faceit", day: "2026-10-20" };
    expect(cardHref(current)).toBe("/staff/players?player=p1&period=7&tab=tests&type=cog&src=faceit&day=2026-10-20");
    expect(cardHref(current, { period: 30 })).toBe(
      "/staff/players?player=p1&tab=tests&type=cog&src=faceit&day=2026-10-20",
    );
    expect(cardHref(current, { tab: "sleep" })).toContain("&tab=sleep");
  });

  it("pickingAnotherPlayerKeepsFiltersButDropsTheDay", () => {
    const current: CardParams = { player: "p1", period: 90, tab: "wheel", type: "game", src: "all", day: "2026-10-20" };
    expect(cardHref(current, { player: "p2" })).toBe("/staff/players?player=p2&period=90&tab=wheel&type=game");
  });
});

describe("loadCard", () => {
  it("emptyTeamHasNoCard", async () => {
    const t = await seedCardTeam(deps);
    const ctx = await requireStaff(deps, t.ids.loneStaff);
    expect(await loadCard(deps, ctx, { player: t.ids.vega }, TZ)).toEqual({ kind: "none" });
  });

  it("cardOfAnotherTeamsPlayerIs404", async () => {
    const t = await seedCardTeam(deps);
    // "404" in the sense of the brief: nothing of the foreign id is shown; the card is the first roster player's.
    for (const id of [t.ids.outsider, t.ids.solo, t.ids.staff, t.ids.otherStaff, "../../etc", "x".repeat(300)]) {
      const card = cardOf(await loadCard(deps, t.ctx, { player: id }, TZ));
      expect(card.params.player, id).toBe(t.ids.vega);
      expect(card.player.nickname).toBe("vega");
      const json = JSON.stringify(card);
      expect(json).not.toContain("zed-outsider");
      expect(json).not.toContain("solo-loner");
      expect(json).not.toContain(id.slice(0, 40));
    }
  });

  it("removedPlayerCardIs404", async () => {
    const t = await seedCardTeam(deps);
    expect(cardOf(await loadCard(deps, t.ctx, { player: t.ids.nova }, TZ)).player.nickname).toBe("nova");
    await removeMember(deps, t.ids.team, t.ids.staff, t.ids.nova);
    const after = cardOf(await loadCard(deps, t.ctx, { player: t.ids.nova }, TZ));
    expect(after.params.player).toBe(t.ids.vega);
    expect(JSON.stringify(after)).not.toContain("nova");
  });

  it("loadsTheSyncedCollectionsThroughOnePlayerDocumentsCall", async () => {
    const t = await seedCardTeam(deps);
    const reads: string[] = [];
    const counting = {
      ...deps,
      sql: new Proxy(deps.sql, {
        apply(target, thisArg, args: unknown[]) {
          const first = args[0];
          if (Array.isArray(first) && "raw" in first) reads.push(first.join("?"));
          return Reflect.apply(target as never, thisArg, args);
        },
      }),
    };
    await loadCard(counting, t.ctx, { player: t.ids.vega }, TZ);
    expect(reads.filter((q) => /select collection, document from sync_documents/.test(q))).toHaveLength(1);
    expect([...CARD_COLLECTIONS].sort()).toEqual(
      ["cognitive", "faceit", "nutrition", "player-survey", "screen-time", "sleep", "wellbeing"],
    );
  });

  it("kpiStripFromRealSeries", async () => {
    const t = await seedCardTeam(deps);
    const card = cardOf(await loadCard(deps, t.ctx, { player: t.ids.vega }, TZ));
    // Score days 20, 22, 24, 26 Oct: 70, 50, 90, 70 -> mean 70; halves 60 and 80.
    // Readiness (owner's formula) on the same days: 81, 58, 92, 48 -> mean 69.75; halves 69.5 and 70.
    // Focus by day: 22 Oct 8 (9 and 7), 25 Oct 6; fatigue: 22 Oct 5 (2 and 8), 25 Oct 9.
    expect(card.kpis).toEqual([
      { label: "Средний score", value: "70", note: { text: "+20.0 за период", tone: "up" } },
      { label: "Индекс состояния", value: "70", note: { text: "+0.5 за период", tone: "up" } },
      { label: "Фокус high", value: "1 из 2", note: { text: "дней с фокусом 8+", tone: "flat" } },
      { label: "Усталость high", value: "1 из 2", note: { text: "дней с усталостью 8+", tone: "flat" } },
    ]);
  });

  it("aFallingSeriesIsDownWithATypographicMinus", async () => {
    const t = await seedCardTeam(deps);
    const card = cardOf(await loadCard(deps, t.ctx, { player: t.ids.nova }, TZ));
    // Score 80 then 60; readiness 92 then 69 (cognitive alone, scaled): mean 80.5 -> 81.
    expect(card.kpis[0]).toMatchObject({ value: "70", note: { text: "−20.0 за период", tone: "down" } });
    expect(card.kpis[1]).toMatchObject({ value: "81", note: { text: "−23.0 за период", tone: "down" } });
    // No game tests at all: the counters have no days to count.
    expect(card.kpis[2]).toEqual({ label: "Фокус high", value: "нет данных", note: { text: "дней с фокусом 8+", tone: "flat" } });
  });

  it("periodLimitsTheDays", async () => {
    const t = await seedCardTeam(deps);
    await putDoc(deps, t.ids.nova, "cognitive", {
      results: [
        { id: "old", kind: "reaction", date: "2026-10-05T08:00:00Z", score: 100, metric: "x" },
        { id: "n1", kind: "reaction", date: "2026-10-24T08:00:00Z", score: 80, metric: "250 мс" },
        { id: "n2", kind: "aim", date: "2026-10-26T08:00:00Z", score: 60, metric: "60%" },
      ],
    });
    const month = cardOf(await loadCard(deps, t.ctx, { player: t.ids.nova }, TZ));
    // Days 5, 24, 26 Oct: 100, 80, 60 -> mean 80; halves (floor(3 / 2) = 1): 100 and 70.
    expect(month.kpis[0]).toMatchObject({ value: "80", note: { text: "−30.0 за период" } });
    expect(month.rangeLabel).toBe("27 сен – 26 окт");
    const week = cardOf(await loadCard(deps, t.ctx, { player: t.ids.nova, period: "7" }, TZ));
    expect(week.kpis[0]).toMatchObject({ value: "70", note: { text: "−20.0 за период" } });
    expect(week.rangeLabel).toBe("20 окт – 26 окт");
    const quarter = cardOf(await loadCard(deps, t.ctx, { player: t.ids.nova, period: "90" }, TZ));
    expect(quarter.rangeLabel).toBe("29 июл – 26 окт");
  });

  it("oneDayHasNoDynamics", async () => {
    const t = await seedCardTeam(deps);
    await putDoc(deps, t.ids.ghost, "cognitive", {
      results: [{ id: "g", kind: "aim", date: "2026-10-26T08:00:00Z", score: 66, metric: "x" }],
    });
    const card = cardOf(await loadCard(deps, t.ctx, { player: t.ids.ghost }, TZ));
    expect(card.kpis[0]).toEqual({ label: "Средний score", value: "66", note: { text: "нет данных", tone: "flat" } });
  });

  it("testsTableRealNames", async () => {
    const t = await seedCardTeam(deps);
    const rows = cardOf(await loadCard(deps, t.ctx, { player: t.ids.vega, tab: "tests" }, TZ)).tests.rows;
    expect(rows).toEqual([
      { date: "Сегодня", test: "Точность прицела", score: "70", state: "48" },
      { date: "Вчера", test: "До тренировки", score: "—", state: "—" },
      { date: "24 окт", test: "Объём памяти", score: "90", state: "92" },
      { date: "22 окт", test: "После тренировки", score: "—", state: "58" },
      { date: "22 окт", test: "До тренировки", score: "—", state: "58" },
      { date: "22 окт", test: "Тест Струпа", score: "50", state: "58" },
      { date: "20 окт", test: "Go / No-Go", score: "80", state: "81" },
      { date: "20 окт", test: "Скорость реакции", score: "60", state: "81" },
    ]);
  });

  it("testTypeFilterNarrowsTheTable", async () => {
    const t = await seedCardTeam(deps);
    const names = async (type: string) =>
      cardOf(await loadCard(deps, t.ctx, { player: t.ids.vega, type }, TZ)).tests.rows.map((r) => r.test);
    expect(await names("all")).toHaveLength(8);
    expect(await names("cog")).toEqual(["Точность прицела", "Объём памяти", "Тест Струпа", "Go / No-Go", "Скорость реакции"]);
    expect(await names("game")).toEqual(["До тренировки", "После тренировки", "До тренировки"]);
    expect(await names("week")).toHaveLength(8); // not an option any more: the default
  });

  it("testsTableIsCappedAndSaysSo", async () => {
    const t = await seedCardTeam(deps);
    const results = Array.from({ length: 45 }, (_, i) => ({
      id: `r${i}`, kind: "reaction", date: `2026-10-${String(1 + (i % 25)).padStart(2, "0")}T0${i % 9}:00:00Z`, score: 50, metric: "x",
    }));
    await putDoc(deps, t.ids.ghost, "cognitive", { results });
    const tests = cardOf(await loadCard(deps, t.ctx, { player: t.ids.ghost }, TZ)).tests;
    expect(tests.rows).toHaveLength(30);
    expect(tests.total).toBe(45);
  });

  it("testsChartPointsOnlyForDaysWithData", async () => {
    const t = await seedCardTeam(deps);
    const chart = cardOf(await loadCard(deps, t.ctx, { player: t.ids.vega, period: "7" }, TZ)).tests.chart;
    expect(chart).not.toBeNull();
    // Seven days 20..26 Oct: x = 40 + i * 850 / 6; score on 20, 22, 24, 26 -> i = 0, 2, 4, 6, y = 180 - v * 1.6.
    expect(chart?.score.dots).toContain("M36.5 68"); // (40, 180 - 70 * 1.6 = 68), radius 3.5
    expect(chart?.score.line).toBe(""); // no two consecutive days: nothing to join
    // Readiness on the same days (81, 58, 92, 48): y = 180 - v * 1.6 = 50.4, 87.2, 32.8, 103.2 at x = 40, 323.3, 606.7, 890.
    expect(chart?.state.line).toBe("");
    expect(chart?.state.dots).toBe(
      ["M36.5 50.4", "M319.8 87.2", "M603.2 32.8", "M886.5 103.2"].map((m) => `${m}a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0`).join(""),
    );
    expect(chart?.aria).toBe("Score и Индекс состояния игрока vega за период");
  });

  it("sleepTabAveragesAndShortNights", async () => {
    const t = await seedCardTeam(deps);
    const sleep = cardOf(await loadCard(deps, t.ctx, { player: t.ids.vega, tab: "sleep" }, TZ)).sleep;
    expect(sleep.summary).toBe("Средняя продолжительность 6 ч 45 мин · ночей короче 7 ч: 1 из 2");
    expect(sleep.rows).toEqual([
      { date: "Вчера → сегодня", start: "23:30", end: "05:00", duration: "5 ч 30 мин", short: true },
      { date: "23 окт → 24 окт", start: "23:00", end: "07:00", duration: "8 ч 00 мин", short: false },
    ]);
  });

  it("sleepTabShowsTheLastTenNights", async () => {
    const t = await seedCardTeam(deps);
    const sessions = Array.from({ length: 14 }, (_, i) => {
      const day = String(10 + i).padStart(2, "0");
      return { id: `s${i}`, start: `2026-10-${day}T20:00:00Z`, end: `2026-10-${String(11 + i).padStart(2, "0")}T04:00:00Z` };
    });
    await putDoc(deps, t.ids.ghost, "sleep", { sessions });
    const sleep = cardOf(await loadCard(deps, t.ctx, { player: t.ids.ghost, tab: "sleep" }, TZ)).sleep;
    expect(sleep.rows).toHaveLength(10);
    expect(sleep.summary).toBe("Средняя продолжительность 8 ч 00 мин · ночей короче 7 ч: 0 из 14");
  });

  it("cardWithoutAnyDataSaysNoData", async () => {
    const t = await seedCardTeam(deps);
    const card = cardOf(await loadCard(deps, t.ctx, { player: t.ids.ghost, tab: "tests" }, TZ));
    expect(card.kpis.map((k) => k.value)).toEqual(["нет данных", "нет данных", "нет данных", "нет данных"]);
    expect(card.kpis.map((k) => k.note?.text)).toEqual([undefined, undefined, "дней с фокусом 8+", "дней с усталостью 8+"]);
    expect(card.tests).toEqual({ chart: null, rows: [], total: 0 });
    expect(card.sleep).toEqual({ summary: "нет данных", rows: [] });
    expect(card.player.lastActivity).toBe("нет данных");
  });

  it("identityFromTheRosterOnly", async () => {
    const t = await seedCardTeam(deps);
    await deps.sql`update users set faceit_url = 'https://www.faceit.com/ru/players/Vega' where id = ${t.ids.vega}`;
    const card = cardOf(await loadCard(deps, t.ctx, { player: t.ids.vega }, TZ));
    expect(card.player).toMatchObject({
      id: t.ids.vega, nickname: "vega", initials: "VE", faceitUrl: "https://www.faceit.com/ru/players/Vega",
    });
    expect(card.player.lastActivity).toMatch(/^сегодня, \d\d:\d\d$/);
  });
});
