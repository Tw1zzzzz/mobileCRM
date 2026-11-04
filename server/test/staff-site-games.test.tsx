import { renderToStaticMarkup } from "react-dom/server";
import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CardFrame } from "@/components/staff/card/CardFrame";
import { requireStaff } from "../lib/staff/access";
import { loadCard, parseCardParams, type CardData } from "../lib/staff/card";
import { saveGame, type GameInput, type GameMapInput, type GameRowInput } from "../lib/staff/games";
import { loadStatistics, type StatsPage } from "../lib/staff/statistics-page";
import { resetDb, testDeps } from "./helpers";
import { putDoc, seedCardTeam, type CardTeam } from "./staff-card-fixture";
import { FACEIT_IDS, KEY, TZ, fakeFaceit, item, type FakeFaceit } from "./staff-statistics-fixture";

/**
 * Hand-entered games (pracc / tournament) in the player card's «Игра» and «Корреляции» tabs and in «Статистика».
 * Today is 2026-10-26; the 30-day period starts on 2026-09-27.
 */
const deps = testDeps();

beforeEach(async () => {
  await resetDb(deps.sql);
  vi.stubEnv("FACEIT_API_KEY", KEY);
});
afterEach(() => vi.unstubAllEnvs());
afterAll(() => deps.sql.end());

const text = (markup: string): string => markup.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim();

function tileMap(markup: string): Record<string, string> {
  return Object.fromEntries(
    markup.split(/<h3[^>]*>/).slice(1).flatMap((part) =>
      [...part.matchAll(/<dt[^>]*>([^<]*)<\/dt><dd[^>]*>([^<]*)<\/dd>/g)].map((m) => [m[1], m[2]] as [string, string])),
  );
}

async function open(t: CardTeam, query: Record<string, string>, faceit: FakeFaceit | null = null) {
  const data = await loadCard(deps, t.ctx, query, TZ, faceit ? { http: faceit.http, apiKey: KEY } : {});
  if (data.kind !== "card") throw new Error("expected a card");
  return { data: data as Extract<CardData, { kind: "card" }>, markup: renderToStaticMarkup(<CardFrame card={data} />) };
}

const line = (over: Partial<GameRowInput>): GameRowInput => ({ kills: 10, deaths: 10, assists: 1, adr: 70, ...over });
const mapOf = (over: Partial<GameMapInput>): GameMapInput => ({
  map: "mirage", ourScore: 13, theirScore: 7, pistol1: "won", pistol2: "lost", rows: [], ...over,
});

/**
 * Vega: pracc 24 Oct, mirage 13:7 win (20/10/4, ADR 80, KAST 70, FK 3, FD 1, UDR 20, multikills 2, clutches 1, flash 1.5)
 * and inferno 9:13 loss (10/15/2, ADR 50, KAST 50, FK 1, FD 2); tournament 25 Oct, nuke 12:12 draw (12/12/1, ADR 70).
 * Nova played only the mirage map (15/12/3, ADR 60); a stand-in "ringer" played it too. A pracc long before the period.
 */
async function seedGames(t: CardTeam): Promise<void> {
  const { vega, nova } = t.ids;
  const pracc: GameInput = {
    kind: "pracc", playedOn: "2026-10-24", opponent: "Natus Vincere",
    maps: [
      mapOf({
        rows: [
          line({ playerId: vega, kills: 20, deaths: 10, assists: 4, adr: 80, kast: 70, fk: 3, fd: 1, udr: 20, multikills: 2, clutches: 1, flashTime: 1.5 }),
          line({ playerId: nova, kills: 15, deaths: 12, assists: 3, adr: 60 }),
          line({ nickname: "ringer", kills: 5, deaths: 5, assists: 0, adr: 40 }),
        ],
      }),
      mapOf({
        map: "inferno", ourScore: 9, theirScore: 13, pistol1: "lost", pistol2: "lost",
        rows: [line({ playerId: vega, kills: 10, deaths: 15, assists: 2, adr: 50, kast: 50, fk: 1, fd: 2 })],
      }),
    ],
  };
  const tournament: GameInput = {
    kind: "tournament", playedOn: "2026-10-25", opponent: null,
    maps: [mapOf({ map: "nuke", ourScore: 12, theirScore: 12, pistol1: "won", pistol2: "won", rows: [line({ playerId: vega, kills: 12, deaths: 12, assists: 1, adr: 70 })] })],
  };
  const old: GameInput = {
    kind: "pracc", playedOn: "2026-08-01", opponent: "Old",
    maps: [mapOf({ rows: [line({ playerId: vega, kills: 99, deaths: 1, adr: 150 })] })],
  };
  for (const game of [pracc, tournament, old]) await saveGame(deps, t.ctx, game);
}

describe("the card's source filter", () => {
  it("knowsAllPraccFaceitAndTournament", () => {
    for (const src of ["all", "pracc", "faceit", "tournament"]) {
      expect(parseCardParams({ src }, ["p1"])?.src).toBe(src);
    }
    expect(parseCardParams({ src: "prak" }, ["p1"])?.src).toBe("all");
  });

  it("listsTheBoardsFourSourcesInItsOrderAllEnabled", async () => {
    const t = await seedCardTeam(deps);
    const { markup } = await open(t, { player: t.ids.vega, tab: "game", src: "pracc" });
    const select = markup.slice(markup.indexOf('id="f-src"'), markup.indexOf("</select>", markup.indexOf('id="f-src"')));
    const options = [...select.matchAll(/<option value="([^"]*)"([^>]*)>([^<]*)<\/option>/g)].map((m) => ({
      value: m[1], label: m[3], disabled: m[2].includes("disabled"), selected: m[2].includes("selected"),
    }));
    expect(options).toEqual([
      { value: "all", label: "Все", disabled: false, selected: false },
      { value: "pracc", label: "Пракки", disabled: false, selected: true },
      { value: "faceit", label: "Faceit", disabled: false, selected: false },
      { value: "tournament", label: "Турниры", disabled: false, selected: false },
    ]);
  });
});

describe("«Игра» with site games", () => {
  it("cardShowsPraccsForPlayer", async () => {
    const t = await seedCardTeam(deps);
    await seedGames(t);
    const faceit = fakeFaceit({});
    const { markup, data } = await open(t, { player: t.ids.vega, tab: "game", src: "pracc" }, faceit);

    // two maps of the pracc: 30 kills, 25 deaths, 6 assists over 42 rounds; the tournament and the old pracc are not counted
    expect(tileMap(markup)).toMatchObject({
      "Убийства": "30", "Смерти": "25", "Ассисты": "6", "K/D": "1.20",
      "ADR": "65.0", "KPR": "0.71", "Death/round": "0.60", "AVG KR": "0.71", "AVG KD": "1.33",
      "KAST": "60%", "First kills": "4", "First deaths": "3", "UDR": "20.0", "Разница опен-дуэлей": "+1",
      "Ср. мультикиллы": "2.0", "Выигранные клатчи": "1", "Ср. время ослепления": "1.5 с",
      "Всего матчей": "2", "Победы": "1", "Поражения": "1", "Ничьи": "0", "Win-Rate": "50%",
      "Раунды выиграно / всего": "22 / 42", "Round Win-Rate": "52%", "AVG Rounds Won": "11.0", "AVG Rounds Lost": "10.0",
      "Пистолетные выиграно / всего": "1 / 4", "Pistol Win-Rate": "25%",
    });
    expect(faceit.calls).toEqual([]); // site sources never ask Faceit
    expect(data.game?.notice).toBeNull();

    // the list of the player's games, newest first; the opponent is never invented
    const list = text(markup);
    expect(list).toContain("24 окт");
    expect(list).toContain("Natus Vincere");
    expect(list).toContain("Mirage 13:7");
    expect(list).toContain("Inferno 9:13");
    expect(list).not.toContain("Nuke");
    expect(list).not.toContain("ringer");
  });

  it("aPlayerSeesOnlyTheirOwnLinesAndMaps", async () => {
    const t = await seedCardTeam(deps);
    await seedGames(t);
    const nova = tileMap((await open(t, { player: t.ids.nova, tab: "game", src: "pracc" })).markup);
    expect(nova).toMatchObject({
      "Убийства": "15", "Смерти": "12", "K/D": "1.25", "ADR": "60.0", "Всего матчей": "1", "Победы": "1", "Win-Rate": "100%",
      "Раунды выиграно / всего": "13 / 20",
    });
    // no first kills were entered for nova: missing, never zero
    expect(nova["First kills"]).toBe("нет данных");
    expect(nova["KAST"]).toBe("нет данных");
    const ghost = await open(t, { player: t.ids.ghost, tab: "game", src: "pracc" });
    expect(tileMap(ghost.markup)["K/D"]).toBe("нет данных");
    expect(tileMap(ghost.markup)["Всего матчей"]).toBe("0");
    expect(text(ghost.markup)).toContain("В выбранном периоде игр нет");
  });

  it("tournamentsAreCountedSeparatelyAndADrawIsADraw", async () => {
    const t = await seedCardTeam(deps);
    await seedGames(t);
    const tiles = tileMap((await open(t, { player: t.ids.vega, tab: "game", src: "tournament" })).markup);
    expect(tiles).toMatchObject({
      "Убийства": "12", "K/D": "1.00", "Всего матчей": "1", "Победы": "0", "Поражения": "0", "Ничьи": "1", "Win-Rate": "0%",
      "Пистолетные выиграно / всего": "2 / 2", "Pistol Win-Rate": "100%",
    });
  });

  it("anotherTeamsGamesNeverShow", async () => {
    const t = await seedCardTeam(deps);
    await seedGames(t);
    const bravo = await requireStaff(deps, t.ids.otherStaff);
    await saveGame(deps, bravo, {
      kind: "pracc", playedOn: "2026-10-24", maps: [mapOf({ rows: [line({ playerId: t.ids.outsider, kills: 7 })] })],
    });
    // Bravo's staff sees Bravo's game of their own player and nothing of Alpha's
    const foreign = await open({ ...t, ctx: bravo }, { player: t.ids.outsider, tab: "game", src: "pracc" });
    expect(tileMap(foreign.markup)["Убийства"]).toBe("7");
    expect(tileMap(foreign.markup)["Всего матчей"]).toBe("1");
    // and a player id of another team is not a card at all: the first roster player's card is shown
    const probe = await open({ ...t, ctx: bravo }, { player: t.ids.vega, tab: "game", src: "pracc" });
    expect(probe.data.params.player).toBe(t.ids.outsider);
    // Alpha's numbers do not include Bravo's game
    const mine = tileMap((await open(t, { player: t.ids.vega, tab: "game", src: "pracc" })).markup);
    expect(mine["Убийства"]).toBe("30");
  });

  it("cardAllMergesSources", async () => {
    const t = await seedCardTeam(deps);
    await seedGames(t);
    await putDoc(deps, t.ids.vega, "faceit", { nickname: "vega_f", playerId: FACEIT_IDS.vega, snapshots: [] });
    const faceit = fakeFaceit({
      [FACEIT_IDS.vega]: [item("2026-10-23T10:00:00Z", { Kills: "10", Deaths: "10", Assists: "1", ADR: "70", Rounds: "24", Score: "13 / 11" })],
    });
    const { markup } = await open(t, { player: t.ids.vega, tab: "game" }, faceit);

    // Faceit 10/10 + pracc 20/10 + 10/15 + tournament 12/12: 52 kills, 47 deaths over four lines
    expect(tileMap(markup)).toMatchObject({
      "Убийства": "52", "Смерти": "47", "Ассисты": "8", "K/D": "1.11", "ADR": "67.5",
      "Всего матчей": "4", "Победы": "2", "Поражения": "1", "Ничьи": "1", "Win-Rate": "50%",
      "Пистолетные выиграно / всего": "3 / 6", // Faceit has no pistols: only the site maps count
    });
    expect(faceit.calls).toHaveLength(1);

    // the three views of the same data are consistent: all = faceit + pracc + tournament
    const only = tileMap((await open(t, { player: t.ids.vega, tab: "game", src: "faceit" }, faceit)).markup);
    expect(only["Всего матчей"]).toBe("1");
    expect(only["Убийства"]).toBe("10");
  });

  it("allWithoutSiteGamesIsTheFaceitView", async () => {
    const t = await seedCardTeam(deps);
    await putDoc(deps, t.ids.vega, "faceit", { nickname: "vega_f", playerId: FACEIT_IDS.vega, snapshots: [] });
    const faceit = fakeFaceit({ [FACEIT_IDS.vega]: [item("2026-10-23T10:00:00Z", { "Utility Damage": "110" })] });
    const all = tileMap((await open(t, { player: t.ids.vega, tab: "game" }, faceit)).markup);
    const only = tileMap((await open(t, { player: t.ids.vega, tab: "game", src: "faceit" }, faceit)).markup);
    expect(all).toEqual(only);
    expect(all["Ничьи"]).toBe("нет данных");
  });

  it("allShowsSiteGamesWhenFaceitIsDown", async () => {
    const t = await seedCardTeam(deps);
    await seedGames(t);
    const { markup, data } = await open(t, { player: t.ids.vega, tab: "game" }); // no key, no Faceit player
    expect(data.game?.notice?.message).toBe("У игрока не подключён Faceit");
    expect(tileMap(markup)["Убийства"]).toBe("42");
    expect(tileMap(markup)["Всего матчей"]).toBe("3");
  });

  it("onlyFaceitSourceSaysNoFaceitMatchesForAnEmptyPeriod", async () => {
    const t = await seedCardTeam(deps);
    await putDoc(deps, t.ids.vega, "faceit", { nickname: "vega_f", playerId: FACEIT_IDS.vega, snapshots: [] });
    const none = fakeFaceit({ [FACEIT_IDS.vega]: [item("2026-01-05T10:00:00Z")] });
    const { markup } = await open(t, { player: t.ids.vega, tab: "game", src: "faceit" }, none);
    expect(text(markup)).toContain("В выбранном периоде матчей Faceit нет");
  });
});

describe("«Корреляции» with site games", () => {
  it("praccDaysAreCorrelatedWithState", async () => {
    const t = await seedCardTeam(deps);
    // sleep 6, 7, 8 h on 22, 23, 24 Oct; a pracc each day with K/D 1.0, 1.5, 2.0 and ADR 60, 70, 80
    await putDoc(deps, t.ids.vega, "sleep", {
      sessions: ["22", "23", "24"].map((d, i) => ({
        id: `s${d}`, start: new Date(Date.parse(`2026-10-${d}T04:00:00Z`) - (6 + i) * 3_600_000).toISOString(), end: `2026-10-${d}T04:00:00Z`,
      })),
    });
    for (const [i, day] of ["22", "23", "24"].entries()) {
      await saveGame(deps, t.ctx, {
        kind: "pracc", playedOn: `2026-10-${day}`,
        maps: [mapOf({ rows: [line({ playerId: t.ids.vega, kills: 10 + 5 * i, adr: 60 + 10 * i })] })],
      });
    }
    const faceit = fakeFaceit({});
    const { data, markup } = await open(t, { player: t.ids.vega, tab: "corr", src: "pracc" }, faceit);
    expect(faceit.calls).toEqual([]);
    expect(data.corr?.notice).toBeNull();
    const row = data.corr?.rows.find((r) => r.name === "Сон");
    expect(row?.cells.map((c) => c.text)).toEqual(["+1.00", "+1.00", expect.any(String)]);
    expect(text(markup)).toContain("Корреляции");
  });
});

describe("«Статистика» with site games", () => {
  const DAYS = ["2026-10-22", "2026-10-23", "2026-10-24", "2026-10-25"];

  async function open2(t: CardTeam, query: Record<string, string>, faceit: FakeFaceit | null = null) {
    const page: StatsPage = await loadStatistics(deps, t.ctx, query, TZ, faceit ? { http: faceit.http, apiKey: KEY } : {});
    if (page.kind !== "data") throw new Error("expected data");
    return page;
  }

  async function seedSleep(userId: string, hours: number[]): Promise<void> {
    await putDoc(deps, userId, "sleep", {
      sessions: DAYS.map((d, i) => ({
        id: `s${d}`, start: new Date(Date.parse(`${d}T04:00:00Z`) - hours[i] * 3_600_000).toISOString(), end: `${d}T04:00:00Z`,
      })),
    });
  }

  it("statisticsIncludesSiteGames", async () => {
    const t = await seedCardTeam(deps);
    await seedSleep(t.ids.vega, [6, 7, 8, 9]);
    // K/D of the site games by day: 1.0, 1.5, 2.0, 2.5 (no Faceit at all)
    for (const [i, day] of DAYS.entries()) {
      await saveGame(deps, t.ctx, {
        kind: i % 2 === 0 ? "pracc" : "tournament", playedOn: day,
        maps: [mapOf({ rows: [line({ playerId: t.ids.vega, kills: 10 + 5 * i, adr: 60 + 10 * i })] })],
      });
    }
    const faceit = fakeFaceit({});
    const page = await open2(t, { who: t.ids.vega, m: "sleep,kd,adr,wr" }, faceit);
    const cell = (row: string, column: string): string => {
      const matrix = page.view.matrix;
      if (matrix === null) throw new Error("no matrix");
      return matrix.rows[matrix.rows.findIndex((x) => x.name === row)].cells[matrix.head.indexOf(column)].text;
    };
    expect(cell("Сон", "K/D")).toBe("+1.00");
    expect(cell("Сон", "ADR")).toBe("+1.00");
    const kd = page.view.multiples.find((m) => m.id === "kd");
    expect(kd?.hasData).toBe(true);
    expect(kd?.avg).toBe("1.75");
    expect(page.view.game?.tiles.slice(0, 4)).toMatchObject([
      { label: "Сыграно карт", value: "4" },
      { label: "Средний K/D", value: "1.75" },
      { label: "Средний ADR", value: "75.0" },
      { label: "Win Rate", value: "100%" },
    ]);
  });

  it("aDayWithFaceitAndSiteGamesIsTheirMean", async () => {
    const t = await seedCardTeam(deps);
    await putDoc(deps, t.ids.vega, "faceit", { nickname: "vega_f", playerId: FACEIT_IDS.vega, snapshots: [] });
    await saveGame(deps, t.ctx, {
      kind: "pracc", playedOn: "2026-10-24",
      maps: [mapOf({ rows: [line({ playerId: t.ids.vega, kills: 20, deaths: 10, adr: 100 })] })],
    });
    const faceit = fakeFaceit({
      [FACEIT_IDS.vega]: [item("2026-10-24T10:00:00Z", { Kills: "10", Deaths: "10", ADR: "60", Result: "0", Score: "9 / 13" })],
    });
    const page = await open2(t, { who: t.ids.vega, m: "kd,adr,wr" }, faceit);
    // one day, two lines: K/D (2.0 + 1.0) / 2, ADR (100 + 60) / 2, Win Rate (100 + 0) / 2
    expect(page.view.multiples.map((m) => [m.id, m.avg])).toEqual([["kd", "1.50"], ["adr", "80.0"], ["wr", "50%"]]);
    expect(page.view.game?.tiles[0]).toEqual({ label: "Сыграно карт", value: "2" });
  });

  it("aMapPlayedByTwoSelectedPlayersIsOneMap", async () => {
    const t = await seedCardTeam(deps);
    await saveGame(deps, t.ctx, {
      kind: "pracc", playedOn: "2026-10-24",
      maps: [mapOf({ rows: [line({ playerId: t.ids.vega, kills: 20 }), line({ playerId: t.ids.nova, kills: 10 })] })],
    });
    const page = await open2(t, { m: "kd,adr" }); // team mode
    expect(page.view.game?.tiles.slice(0, 2)).toEqual([
      { label: "Сыграно карт", value: "1" },
      { label: "Средний K/D", value: "1.50" }, // each player's own line
    ]);
  });

  it("siteGamesOfThePeriodOnly", async () => {
    const t = await seedCardTeam(deps);
    await saveGame(deps, t.ctx, {
      kind: "pracc", playedOn: "2026-08-01",
      maps: [mapOf({ rows: [line({ playerId: t.ids.vega, kills: 99 })] })],
    });
    const page = await open2(t, { who: t.ids.vega, m: "kd,adr" });
    expect(page.view.multiples.find((m) => m.id === "kd")?.hasData).toBe(false);
  });

  it("noGameMetricMeansNoGameQuery", async () => {
    const t = await seedCardTeam(deps);
    await saveGame(deps, t.ctx, {
      kind: "pracc", playedOn: "2026-10-24",
      maps: [mapOf({ rows: [line({ playerId: t.ids.vega })] })],
    });
    const page = await open2(t, { who: t.ids.vega, m: "sleep,mood" });
    expect(page.view.game).toBeNull();
  });
});
