import { renderToStaticMarkup } from "react-dom/server";
import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { CardFrame } from "@/components/staff/card/CardFrame";
import { PeriodChips, Roster } from "@/components/staff/players/Roster";
import { chartLine } from "../lib/staff/card-charts";
import { cardHref, loadCard, type CardData, type CardParams } from "../lib/staff/card";
import { loadPlayers } from "../lib/staff/players";
import { resetDb, testDeps } from "./helpers";
import { seedCardTeam, type CardTeam } from "./staff-card-fixture";

const deps = testDeps();
const TZ = "Europe/Moscow";

beforeEach(async () => {
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

/** Text of the markup without tags, to assert on what a person reads. */
function text(markup: string): string {
  return markup.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim();
}

/** The `href`s of the markup as a browser sees them. */
function hrefs(markup: string): string[] {
  return [...markup.matchAll(/href="([^"]*)"/g)].map((m) => m[1].replaceAll("&amp;", "&"));
}

function card(data: CardData): Extract<CardData, { kind: "card" }> {
  if (data.kind !== "card") throw new Error("expected a card");
  return data;
}

async function frame(t: CardTeam, query: Record<string, string>): Promise<{ markup: string; data: ReturnType<typeof card> }> {
  const data = card(await loadCard(deps, t.ctx, query, TZ));
  return { markup: renderToStaticMarkup(<CardFrame card={data} />), data };
}

/** «Скоро», but not the start of «Скорость реакции». */
const SOON = /Скоро(?![а-я])/;
const TABS = ["Профиль", "Тесты", "Игра", "Корреляции", "Колесо баланса", "Питание", "Сон"];

describe("card frame", () => {
  it("identityTabsAndFiltersFromTheBoard", async () => {
    const t = await seedCardTeam(deps);
    const { markup } = await frame(t, { player: t.ids.vega, tab: "tests" });
    const words = text(markup);
    expect(markup).toContain('aria-labelledby="pname"');
    expect(words).toContain("VE");
    expect(words).toContain("vega");
    expect(words).toMatch(/Последняя активность: сегодня, \d\d:\d\d/);
    expect(words).toContain("27 сен – 26 окт");
    // all seven tab links are there, the chosen one is the current page
    const nav = markup.slice(markup.indexOf('aria-label="Разделы карточки"'));
    for (const name of TABS) expect(nav).toContain(`>${name}</a>`);
    expect(nav).toMatch(/aria-current="page"[^>]*>Тесты</);
    expect(hrefs(nav).slice(0, 7)).toEqual([
      `/staff/players?player=${t.ids.vega}`,
      `/staff/players?player=${t.ids.vega}&tab=tests`,
      `/staff/players?player=${t.ids.vega}&tab=game`,
      `/staff/players?player=${t.ids.vega}&tab=corr`,
      `/staff/players?player=${t.ids.vega}&tab=wheel`,
      `/staff/players?player=${t.ids.vega}&tab=food`,
      `/staff/players?player=${t.ids.vega}&tab=sleep`,
    ]);
  });

  it("testTypeSelectHasTheThreeBoardOptionsAndKeepsTheRest", async () => {
    const t = await seedCardTeam(deps);
    const { markup } = await frame(t, { player: t.ids.vega, tab: "tests", type: "cog", period: "7" });
    expect(markup).toContain('<label for="f-type"');
    const options = [...markup.matchAll(/<option value="([^"]*)"[^>]*>([^<]*)<\/option>/g)].map((m) => [m[1], m[2]]);
    expect(options).toEqual([["all", "Все тесты"], ["cog", "Когнитивные"], ["game", "Игровые до/после"]]);
    expect(markup).toMatch(/<option value="cog" selected/);
    expect(markup).not.toContain("Еженедельные");
    // the form carries the selection except the type itself
    const hidden = [...markup.matchAll(/<input type="hidden" name="([^"]*)" value="([^"]*)"/g)].map((m) => [m[1], m[2]]);
    expect(hidden).toEqual([["player", t.ids.vega], ["period", "7"], ["tab", "tests"]]);
    expect(markup).toContain('action="/staff/players"');
  });

  it("kpiStripFromRealSeries", async () => {
    const t = await seedCardTeam(deps);
    const { markup } = await frame(t, { player: t.ids.vega });
    const words = text(markup);
    expect(words).toContain("Средний score 70 +20,0 за период");
    expect(words).toContain("Индекс состояния 70 +0,5 за период");
    expect(words).toContain("Фокус high 1 из 2 дней с фокусом 8+");
    expect(words).toContain("Усталость high 1 из 2 дней с усталостью 8+");
    expect(markup).toContain('data-tone="up"');
    const nova = await frame(t, { player: t.ids.nova });
    expect(nova.markup).toContain('data-tone="down"');
    expect(text(nova.markup)).toContain("−20,0 за период");
  });

  it("tabsNotBuiltYetSaySoon", async () => {
    const t = await seedCardTeam(deps);
    for (const tab of ["profile", "game", "corr"]) {
      const { markup } = await frame(t, { player: t.ids.vega, tab });
      expect(text(markup), tab).toMatch(SOON);
    }
    for (const tab of ["tests", "sleep", "wheel", "food"]) {
      const { markup } = await frame(t, { player: t.ids.vega, tab });
      expect(text(markup), tab).not.toMatch(SOON);
    }
  });

  it("testsTableRealNames", async () => {
    const t = await seedCardTeam(deps);
    const { markup } = await frame(t, { player: t.ids.vega, tab: "tests" });
    const words = text(markup);
    expect(markup).toContain('aria-label="История тестов"');
    expect(words).toContain("Дата Тест Score Индекс состояния");
    for (const name of ["Скорость реакции", "Go / No-Go", "Тест Струпа", "Объём памяти", "Точность прицела", "До тренировки", "После тренировки"]) {
      expect(words, name).toContain(name);
    }
    expect(words).toContain("Сегодня Точность прицела 70 48");
    expect(words).toContain("Вчера До тренировки — —");
    expect(markup.match(/role="row"/g)).toHaveLength(9); // header + 8 tests
    // the chart: legend, board axis, aria label with the nickname, two lines
    expect(words).toContain("Score");
    expect(markup).toContain('viewBox="0 0 900 220"');
    expect(markup).toContain('aria-label="Score и Индекс состояния игрока vega за период"');
    // the dashed orange line is the state index, the solid cyan one is the score
    const paths = markup.match(/<path [^>]*>/g) ?? [];
    expect(paths.filter((p) => p.includes('stroke="#ffaa3c"') && p.includes('stroke-dasharray="8 6"'))).toHaveLength(1);
    const score = paths.filter((p) => p.includes('stroke="#3fd0ff"'));
    expect(score).toHaveLength(1);
    expect(score[0]).not.toContain("stroke-dasharray");
    expect(paths.filter((p) => p.includes("stroke-dasharray"))).toHaveLength(1);
    for (const label of [">100<", ">50<", ">0<"]) expect(markup).toContain(label);
  });

  it("testsTypeFilterShowsOnlyThatKind", async () => {
    const t = await seedCardTeam(deps);
    const game = text((await frame(t, { player: t.ids.vega, tab: "tests", type: "game" })).markup);
    expect(game).toContain("До тренировки");
    expect(game).not.toContain("Точность прицела");
    const cog = text((await frame(t, { player: t.ids.vega, tab: "tests", type: "cog" })).markup);
    expect(cog).toContain("Точность прицела");
    expect(cog).not.toContain("До тренировки");
  });

  it("sleepTabAveragesAndShortNights", async () => {
    const t = await seedCardTeam(deps);
    const { markup } = await frame(t, { player: t.ids.vega, tab: "sleep" });
    const words = text(markup);
    expect(markup).toContain('aria-label="Дневник сна"');
    expect(words).toContain("Средняя продолжительность 6 ч 45 мин · ночей короче 7 ч: 1 из 2");
    expect(words).toContain("Дата сна Начало Окончание Длительность");
    expect(words).toContain("Вчера → сегодня 23:30 05:00 5 ч 30 мин");
    expect(words).toContain("23 окт → 24 окт 23:00 07:00 8 ч 00 мин");
    // only the short night is marked
    expect(markup).toMatch(/class="[^"]*stBad[^"]*">5 ч 30 мин</);
    expect(markup).not.toMatch(/class="[^"]*stBad[^"]*">8 ч 00 мин</);
  });

  it("cardWithoutAnyDataSaysNoData", async () => {
    const t = await seedCardTeam(deps);
    for (const tab of ["tests", "sleep"]) {
      const { markup } = await frame(t, { player: t.ids.ghost, tab });
      const words = text(markup);
      expect(markup, tab).not.toMatch(/NaN|undefined|null|Infinity/);
      // the four tiles
      expect(words).toContain("Средний score нет данных");
      expect(words).toContain("Индекс состояния нет данных");
      expect(words).toContain("Фокус high нет данных дней с фокусом 8+");
      expect(words).toContain("Усталость high нет данных дней с усталостью 8+");
      expect(words).toContain("Последняя активность: нет данных");
      expect(markup).not.toContain("<svg");
      expect(markup).not.toMatch(/>0</);
    }
    expect(text((await frame(t, { player: t.ids.ghost, tab: "tests" })).markup)).toMatch(/нет данных[\s\S]*Дата Тест Score Индекс состояния нет данных/);
    expect(text((await frame(t, { player: t.ids.ghost, tab: "sleep" })).markup)).toContain("нет данных Дата сна Начало Окончание Длительность нет данных");
  });

  it("noDemoStringsInCard", async () => {
    const t = await seedCardTeam(deps);
    const demo =
      /vexa|frost|Kairo|m1rage|zeNN|Atlant|Coach|Демо|State Index|Visual Search|2-Back|Stroop Switch|Spatial Span|Батарея|Опросник выгорания|Командное взаимодействие|Еженедельные|Игровой ·|Холодный аналитик|IGL/;
    for (const tab of ["profile", "tests", "game", "corr", "wheel", "food", "sleep"]) {
      for (const player of [t.ids.vega, t.ids.nova, t.ids.ghost]) {
        const { markup } = await frame(t, { player, tab });
        expect(text(markup), `${tab} ${player}`).not.toMatch(demo);
      }
    }
  });

  it("noCardForAnEmptyTeam", () => {
    expect(renderToStaticMarkup(<CardFrame card={{ kind: "none" }} />)).toBe("");
  });
});

describe("roster and header links", () => {
  async function roster(t: CardTeam, query: Record<string, string>) {
    const page = await loadPlayers(deps, t.ctx, query);
    const data = card(await loadCard(deps, t.ctx, query, TZ));
    return { markup: renderToStaticMarkup(<Roster roster={page.roster} params={data.params} />), data };
  }

  it("rosterCardsAreLinks", async () => {
    const t = await seedCardTeam(deps);
    const { markup } = await roster(t, {});
    expect(hrefs(markup)).toEqual([
      `/staff/players?player=${t.ids.vega}`,
      `/staff/players?player=${t.ids.nova}`,
      `/staff/players?player=${t.ids.ghost}`,
    ]);
    expect(markup).not.toContain("<button");
  });

  it("rosterLinksKeepThePeriodTabAndFilters", async () => {
    const t = await seedCardTeam(deps);
    const { markup } = await roster(t, { player: t.ids.nova, period: "90", tab: "sleep", type: "game", day: "2026-10-20" });
    expect(hrefs(markup)).toEqual([
      `/staff/players?player=${t.ids.vega}&period=90&tab=sleep&type=game`,
      `/staff/players?player=${t.ids.nova}&period=90&tab=sleep&type=game`,
      `/staff/players?player=${t.ids.ghost}&period=90&tab=sleep&type=game`,
    ]);
  });

  it("theSelectedCardIsMarked", async () => {
    const t = await seedCardTeam(deps);
    const { markup } = await roster(t, { player: t.ids.nova });
    const marked = [...markup.matchAll(/<a [^>]*aria-current="true"[^>]*>/g)].map((m) => m[0]);
    expect(marked).toHaveLength(1);
    expect(marked[0]).toContain(`player=${t.ids.nova}`);
    expect(marked[0]).toMatch(/class="[^"]*cardOn/);
  });

  it("anUnknownPlayerMarksTheFirstAndLeaksNothing", async () => {
    const t = await seedCardTeam(deps);
    for (const player of [t.ids.outsider, t.ids.solo, t.ids.staff, "garbage"]) {
      const { markup, data } = await roster(t, { player });
      const page = markup + renderToStaticMarkup(<CardFrame card={data} />);
      expect(page, player).not.toContain("zed-outsider");
      expect(page, player).not.toContain("solo-loner");
      if (player !== t.ids.staff) expect(page, player).not.toContain(player);
      expect(page).toContain(`player=${t.ids.vega}`);
      expect(page).toMatch(/aria-current="true"/);
      expect(data.params.player).toBe(t.ids.vega);
    }
  });

  it("periodChipsKeepPlayer", async () => {
    const t = await seedCardTeam(deps);
    const { data } = await roster(t, { player: t.ids.nova, tab: "tests", type: "cog" });
    const markup = renderToStaticMarkup(<PeriodChips params={data.params} />);
    expect(text(markup)).toBe("7 дней 30 дней 90 дней");
    expect(markup).toContain('aria-label="Период"');
    expect(hrefs(markup)).toEqual([
      `/staff/players?player=${t.ids.nova}&period=7&tab=tests&type=cog`,
      `/staff/players?player=${t.ids.nova}&tab=tests&type=cog`,
      `/staff/players?player=${t.ids.nova}&period=90&tab=tests&type=cog`,
    ]);
    // 30 days is the default and the current one
    expect(markup.match(/aria-current="true"/g)).toHaveLength(1);
    expect(markup).toMatch(/aria-current="true"[^>]*>30 дней</);
    const week = renderToStaticMarkup(<PeriodChips params={{ ...data.params, period: 7 }} />);
    expect(week).toMatch(/aria-current="true"[^>]*>7 дней</);
  });
});

describe("tests chart geometry", () => {
  const days = ["2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05"];

  it("consecutiveDaysAreJoinedAndAGapBreaksTheLine", () => {
    // x = 40 + i * 850 / 4; y = 180 - v * 1.6
    const line = chartLine(days, { "2026-10-01": 50, "2026-10-02": 100, "2026-10-04": 0, "2026-10-05": 25 });
    expect(line.line).toBe("M40 100 L252.5 20 M677.5 180 L890 140");
    expect(line.dots).toBe("");
  });

  it("aLoneDayIsADotNotALine", () => {
    const line = chartLine(days, { "2026-10-03": 50 });
    expect(line.line).toBe("");
    expect(line.dots).toBe("M461.5 100a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0");
  });

  it("noDataIsNoPath", () => {
    expect(chartLine(days, {})).toEqual({ line: "", dots: "" });
  });

  it("valuesOutsideTheAxisAreClamped", () => {
    expect(chartLine(["2026-10-01", "2026-10-02"], { "2026-10-01": 140, "2026-10-02": -5 }).line).toBe("M40 20 L890 180");
  });
});

describe("hrefs", () => {
  it("cardHrefEncodesTheId", () => {
    const base: CardParams = { player: "a b&c", period: 30, tab: "profile", type: "all", src: "all", day: null };
    expect(cardHref(base)).toBe("/staff/players?player=a%20b%26c");
  });
});
