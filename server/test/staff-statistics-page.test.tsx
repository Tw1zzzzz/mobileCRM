import { readFileSync } from "node:fs";
import { join } from "node:path";
import { renderToStaticMarkup } from "react-dom/server";
import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { StatisticsView } from "@/components/staff/statistics/StatisticsView";
import { NAV_GROUPS, isActive } from "../lib/staff/nav";
import { loadStatistics } from "../lib/staff/statistics-page";
import { resetDb, testDeps } from "./helpers";
import { seedCardTeam, type CardTeam } from "./staff-card-fixture";
import {
  FACEIT_IDS, KEY, TZ, daysBefore, fakeFaceit, matchesFor, seedState, type DayRow, type FakeFaceit,
} from "./staff-statistics-fixture";

const deps = testDeps();
const TODAY = "2026-10-26";
const DAYS = daysBefore(TODAY, 10);
const SLEEP = [6, 7, 8, 6.5, 7.5, 8.5, 5.5, 9, 7, 6];
const MOOD = [5, 6, 4, 7, 5, 6, 8, 3, 6, 5];

beforeEach(async () => {
  await resetDb(deps.sql);
  vi.stubEnv("FACEIT_API_KEY", KEY);
});
afterEach(() => {
  vi.unstubAllEnvs();
});
afterAll(() => deps.sql.end());

function rows(count = 10): DayRow[] {
  return DAYS.slice(10 - count).map((day, i) => ({ day, sleep: SLEEP[i], mood: MOOD[i], screen: 2 + (i % 4), elo: 2000 + 10 * i }));
}

/** The page body for a query, as markup. */
async function render(t: CardTeam, query: Record<string, string>, faceit: FakeFaceit | null = null): Promise<string> {
  const page = await loadStatistics(deps, t.ctx, query, TZ, faceit ? { http: faceit.http, apiKey: KEY } : {});
  return renderToStaticMarkup(<StatisticsView page={page} />);
}

function text(markup: string): string {
  return markup.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim();
}

function hrefs(markup: string): string[] {
  return [...markup.matchAll(/href="([^"]*)"/g)].map((m) => m[1].replace(/&amp;/g, "&"));
}

async function seeded(): Promise<{ t: CardTeam; faceit: () => FakeFaceit }> {
  const t = await seedCardTeam(deps);
  await seedState(deps, t.ids.vega, rows(), { playerId: FACEIT_IDS.vega });
  await seedState(deps, t.ids.nova, rows().map((r) => ({ ...r, sleep: (r.sleep as number) + 1 })), { playerId: FACEIT_IDS.nova });
  const matches = (shift: number): unknown[] =>
    matchesFor(DAYS.map((day, i) => ({ day, kills: 2 * SLEEP[i] + shift, adr: 70 + i })));
  return { t, faceit: () => fakeFaceit({ [FACEIT_IDS.vega]: matches(0), [FACEIT_IDS.nova]: matches(2) }) };
}

describe("«Статистика» page", () => {
  it("keepsHeroAndShowsTheRangeLabel", async () => {
    const { t, faceit } = await seeded();
    const words = text(await render(t, {}, faceit()));
    expect(words).toContain("Как менялись состояние и форма — и как это связано с игрой.");
    expect(words).toContain("Что из состояния влияет на игру");
    expect(words).toContain("27 сен – 26 окт 2026 · 30 дней");
    expect(words).toContain("Вся команда");
    for (const nick of ["vega", "nova", "ghost"]) expect(words).toContain(nick);
  });

  it("pickerShowsCountAndLimit", async () => {
    const { t, faceit } = await seeded();
    const defaults = await render(t, {}, faceit());
    const words = text(defaults);
    expect(words).toContain("Выберите метрики для сравнения");
    expect(words).toContain("Слева состояние, справа игра. Выбрано 6 из 8");
    expect(words).toContain("Сбросить выбор");
    expect(words).toContain("Состояние");
    expect(words).toContain("Игра · Faceit");
    expect(words).not.toContain("пракки");
    expect(words).not.toContain("Можно выбрать до 8 метрик");
    // selected chips are pressed and link to the selection without them; the others add themselves
    expect(defaults).not.toContain("aria-pressed");
    expect(defaults).toMatch(/aria-current="true"[^>]*href="\/staff\/statistics\?m=sleep,screen,elo,kd,adr"[^>]*>Настроение</);
    expect(hrefs(defaults)).toContain("/staff/statistics?m=mood,sleep,screen,elo,kd,adr,energy");
    // KAST is not offered
    expect(defaults).not.toContain("KAST");

    // eight chosen: no chip can add a ninth, and the board's message says so
    const eight = await render(t, { m: "mood,energy,sleep,screen,cog,balance,kcal,elo" }, faceit());
    expect(text(eight)).toContain("Выбрано 8 из 8");
    expect(text(eight)).toContain("Можно выбрать до 8 метрик — снимите одну, чтобы добавить другую");
    expect(eight).toMatch(/aria-disabled="true"[^>]*>K\/D</);
    expect(hrefs(eight).filter((h) => h.includes("kd"))).toEqual([]);
    expect(eight).toMatch(/aria-current="true"[^>]*>Сон</);
  });

  it("resetLinkReturnsToDefaults", async () => {
    const { t } = await seeded();
    const markup = await render(t, { who: t.ids.vega, m: "sleep,kd", period: "90" });
    const reset = markup.match(/<a [^>]*href="([^"]*)"[^>]*>Сбросить выбор</);
    expect(reset?.[1]).toBe(`/staff/statistics?who=${t.ids.vega}&amp;period=90`);
  });

  it("scopeAndPeriodChipsLinkWithTheRestOfTheSelection", async () => {
    const { t, faceit } = await seeded();
    const markup = await render(t, { m: "sleep,kd", period: "90" }, faceit());
    const links = hrefs(markup);
    expect(links).toContain(`/staff/statistics?who=${t.ids.vega}&m=sleep,kd&period=90`);
    expect(links).toContain("/staff/statistics?m=sleep,kd"); // 30 days is the default
    expect(links).toContain("/staff/statistics?m=sleep,kd&period=7");
    expect(links).toContain("/staff/statistics?m=sleep,kd&period=365");
    expect(links.some((h) => h.startsWith("/staff/statistics?m=sleep,kd&period=custom&from="))).toBe(true);
    for (const label of ["7 дней", "30 дней", "90 дней", "1 год", "Свой период"]) expect(text(markup)).toContain(label);
    expect(markup).toMatch(/aria-current="true"[^>]*>90 дней</);
    expect(markup).not.toContain('type="date"');
  });

  it("customPeriodHasADateForm", async () => {
    const { t } = await seeded();
    const markup = await render(t, { who: t.ids.nova, m: "sleep,mood", period: "custom", from: "2026-10-20", to: "2026-10-25" });
    expect(markup).toContain('method="get"');
    expect(markup).toContain('action="/staff/statistics"');
    expect(markup).toMatch(/<input[^>]*type="date"[^>]*name="from"[^>]*value="2026-10-20"/);
    expect(markup).toMatch(/<input[^>]*type="date"[^>]*name="to"[^>]*value="2026-10-25"/);
    expect(markup).toMatch(/<input[^>]*type="hidden"[^>]*name="period"[^>]*value="custom"/);
    expect(markup).toMatch(/<input[^>]*type="hidden"[^>]*name="who"[^>]*value="/);
    expect(markup).toMatch(/<input[^>]*type="hidden"[^>]*name="m"[^>]*value="sleep,mood"/);
    expect(text(markup)).toContain("20 окт – 25 окт 2026 · 6 дней");
    // a bad range shows the last 30 days in the same form
    const bad = await render(t, { period: "custom", from: "2026-10-30", to: "2026-10-01" });
    expect(bad).toMatch(/name="from"[^>]*value="2026-09-27"/);
  });

  it("matrixIsADetailsElementOpenByDefault", async () => {
    const { t, faceit } = await seeded();
    const markup = await render(t, { m: "mood,sleep,kd" }, faceit());
    expect(markup).toMatch(/<details[^>]*\bopen=""/);
    expect(text(markup)).toContain("Таблица корреляций");
    expect(text(markup)).toContain("Все пары выбранных метрик. Голубой — прямая связь, оранжевый — обратная, ярче — сильнее");
    expect(text(markup)).toContain("Свернуть таблицу");
    expect(text(markup)).toContain("Показать таблицу");
    expect(markup).toContain("grid-template-columns:150px repeat(3, minmax(78px, 1fr))");
    expect(markup).toContain("min-width:408px");
    expect(markup).not.toContain("<script");
    // diagonal, a coloured cell, and the colour rule rgba(0,140,255,a) for a direct link
    expect(markup).toMatch(/role="cell"[^>]*>—</);
    expect(markup).toMatch(/background:rgba\(0,140,255,\d\.\d+\)[^>]*>\+\d\.\d\d</);
  });

  it("smallMultiplesDrawOneSvgPerMetricWithGaps", async () => {
    const { t, faceit } = await seeded();
    const markup = await render(t, { m: "sleep,mood,kd,kcal" }, faceit());
    const section = markup.slice(markup.indexOf("Динамика выбранных метрик"));
    expect((section.match(/viewBox="0 0 300 90"/g) ?? []).length).toBe(3);
    expect(text(section)).toContain("Каждая метрика — отдельный график, ось X — даты периода");
    expect(section).toContain('aria-label="Сон: среднее');
    expect(section).toMatch(/stroke="#3FD0FF"/);
    expect(section).toMatch(/stroke="#FFAA3C"/); // the game metric
    // kcal has no meals: its own «нет данных» card, no chart
    expect(text(section)).toMatch(/Калорийность\s+нет данных/);
    expect(text(section)).toContain("27 сен");
    expect(text(section)).toMatch(/мин \d/);
    // team sleep per day: the mean of vega (6..9 h) and nova (one hour more) is 6.0..9.5 h
    expect(text(section)).toContain("мин 6.0 ч · макс 9.5 ч");
  });

  it("gameCardLabelsAndSource", async () => {
    const { t, faceit } = await seeded();
    const words = text(await render(t, {}, faceit()));
    expect(words).toContain("Игровые показатели за период");
    expect(words).toContain("Матчи Faceit");
    for (const label of ["Сыграно карт", "Средний K/D", "Средний ADR", "Win Rate", "ELO на конец периода"]) {
      expect(words).toContain(label);
    }
    expect(words).not.toContain("внесённые вручную");
    // not asked for the game: no card
    expect(text(await render(t, { m: "mood,sleep" }))).not.toContain("Игровые показатели за период");
  });

  it("keyLinkCardsShowTheBoardsParts", async () => {
    const { t, faceit } = await seeded();
    const markup = await render(t, { m: "sleep,kd" }, faceit());
    const words = text(markup);
    expect(words).toContain("Главные связи");
    expect(words).toContain("Сон ↔ K/D");
    expect(words).toMatch(/r = [+−]\d\.\d\d/);
    expect(words).toContain("−1 обратная 0 +1 прямая");
    expect(words).toContain("Что это значит");
    expect(words).toContain("Когда сон выше обычного");
    expect(words).toContain("Сильная прямая связь");
    expect(markup).toMatch(/role="img"[^>]*aria-label="Коэффициент корреляции /);
    expect(markup).toMatch(/style="left:\d+\.\d%;width:\d+\.\d%"/);
  });

  it("tooFewDaysSaysNotEnoughData", async () => {
    const t = await seedCardTeam(deps);
    await seedState(deps, t.ids.vega, rows().slice(-2));
    const markup = await render(t, { who: t.ids.vega, m: "sleep,mood,screen" });
    const words = text(markup);
    // the data exists, so there is no empty page, but no pair has 3 days
    expect(words).not.toContain("Пока нет данных за этот период");
    expect(words).toContain("мало данных");
    expect(words).toContain("Связей пока не найти: у пары метрик должно быть хотя бы 3 общих дня с данными.");
    expect(markup).not.toContain("Что это значит");
    expect((markup.match(/role="cell"[^>]*>мало данных</g) ?? []).length).toBe(6);
    expect(markup).not.toMatch(/NaN|undefined|Infinity/);
  });

  it("teamModeSaysThatDaysArePooled", async () => {
    const { t, faceit } = await seeded();
    const line = "Вся команда: дни всех игроков объединены, поэтому на r влияют и различия между игроками.";
    const team = await render(t, { m: "sleep,kd" }, faceit());
    expect(text(team)).toContain(line);
    // under «Главные связи», before the cards
    expect(team.indexOf("Главные связи")).toBeLessThan(team.indexOf(line));
    expect(text(team)).toContain("Вся команда: среднее по игрокам за день");
    // one player: nothing is pooled
    const one = text(await render(t, { who: t.ids.vega, m: "sleep,kd" }, faceit()));
    expect(one).not.toContain("дни всех игроков объединены");
    expect(one).not.toContain("среднее по игрокам");
    // a team where only one player has data pools nothing either
    await deps.sql`delete from sync_documents where user_id = ${t.ids.nova}`;
    expect(text(await render(t, { m: "sleep,mood" }))).not.toContain("дни всех игроков объединены");
  });

  it("matrixSummaryHasAHeadingAsItsChildNotInsideASpan", async () => {
    const { t } = await seeded();
    const markup = await render(t, { m: "mood,sleep" });
    expect(markup).toMatch(/<summary[^>]*><h2[^>]*>Таблица корреляций<\/h2>/);
    expect(markup).not.toMatch(/<span[^>]*><h2/);
  });

  it("nothingToShowGivesTheStaffEmptyState", async () => {
    const t = await seedCardTeam(deps);
    const words = text(await render(t, { m: "mood,sleep", period: "custom", from: "2026-01-01", to: "2026-01-31" }));
    expect(words).toContain("Пока нет данных за этот период");
    expect(words).toContain("Игроки ещё не заполнили состояние или не подключили Faceit — графики появятся, когда данные синхронизируются.");
    expect(words).not.toContain("Пройдите ежедневные тесты");
    expect(words).not.toContain("Отметить состояние");
    expect(words).not.toContain("Главные связи");
  });

  it("noMetricSelectedAsksToPick", async () => {
    const { t } = await seeded();
    const words = text(await render(t, { m: "" }));
    expect(words).toContain("Выбрано 0 из 8");
    expect(words).toContain("Выберите хотя бы две метрики — лучше одну из состояния и одну из игры.");
    expect(words).not.toContain("Пока нет данных за этот период");
  });

  it("faceitDownStillShowsStateMetrics", async () => {
    const { t } = await seeded();
    const markup = await render(t, { who: t.ids.vega, m: "sleep,mood,kd" }, fakeFaceit({ [FACEIT_IDS.vega]: 500 }));
    const words = text(markup);
    expect(markup).toContain('role="status"');
    expect(words).toContain("Faceit временно недоступен — попробуйте позже");
    expect(words).toMatch(/Сон ↔ Настроение/);
    expect(words).toContain("нет данных");
    expect(markup).not.toContain("Application error");
  });

  it("aColdCacheFailureForOneOfTwoPlayersStillRenders", async () => {
    const t = await seedCardTeam(deps);
    await seedState(deps, t.ids.vega, rows(), { playerId: FACEIT_IDS.vega });
    await seedState(deps, t.ids.nova, rows(), { playerId: FACEIT_IDS.nova });
    const items = matchesFor(DAYS.map((day, i) => ({ day, kills: 2 * SLEEP[i] })));
    const words = text(await render(t, { m: "sleep,kd" }, fakeFaceit({ [FACEIT_IDS.vega]: items, [FACEIT_IDS.nova]: 500 })));
    expect(words).toContain("Данных Faceit нет у 2 из 3 игроков");
    expect(words).toContain("Сон ↔ K/D");
    expect(words).toContain("Сыграно карт 10");
  });

  it("teamLargerThanTwelveSaysSo", async () => {
    const t = await seedCardTeam(deps);
    for (let i = 0; i < 11; i++) {
      await deps.sql`
        insert into users (email, nickname, password_hash, role, team_id, email_verified_at,
          accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at, created_at, updated_at)
        values (${`more${i}@stats.gg`}, ${`more${i}`}, 'x', 'teamPlayer', ${t.ids.team}, ${deps.now()},
          ${deps.now()}, ${deps.now()}, '2026-10-26', ${deps.now()}, ${deps.now()}, ${deps.now()})`;
      deps.advance(1_000);
    }
    const words = text(await render(t, { m: "mood,sleep" }));
    expect(words).toContain("показаны первые 12 игроков");
    expect(text(await render(t, { who: t.ids.vega, m: "mood,sleep" }))).not.toContain("показаны первые 12 игроков");
  });

  it("anEmptyTeamShowsTheCode", async () => {
    const t = await seedCardTeam(deps);
    const lone = await import("../lib/staff/access").then((m) => m.requireStaff(deps, t.ids.loneStaff));
    const page = await loadStatistics(deps, lone, {}, TZ);
    const words = text(renderToStaticMarkup(<StatisticsView page={page} />));
    expect(words).toContain("В команде пока нет игроков");
    expect(words).toContain("Код игрока");
    expect(words).not.toContain("Главные связи");
  });

  it("noSubscriptionOrDemoStrings", async () => {
    const { t, faceit } = await seeded();
    const all = [
      await render(t, {}, faceit()),
      await render(t, { who: t.ids.vega, m: "mood,energy,sleep,screen,cog,balance,kcal,elo" }, faceit()),
      await render(t, { m: "" }),
      await render(t, { period: "custom", from: "2026-01-01", to: "2026-01-02" }),
    ].map(text).join(" ");
    for (const banned of [
      "в подписке", "Тарифы", "тарифы", "Посмотреть", "AI-вывод", "Сформировать вывод", "KAST", "Обзор показателей",
      "Средние значения и динамика", "Atlant", "vexa", "Coach", "Демо", "демо", "Разбор демо", "Отметить состояние",
      "Серия", "Уведомления", "на ваших данных", "Пройдите ежедневные тесты", "Все модули в одном месте",
      "внесённые вручную", "Скрыть блок",
    ]) {
      expect(all, banned).not.toContain(banned);
    }
  });

  it("theFaceitKeyNeverReachesTheMarkup", async () => {
    const { t, faceit } = await seeded();
    const markup = await render(t, {}, faceit());
    expect(markup).not.toContain(KEY);
    expect(markup).not.toContain("FACEIT_API_KEY");
    expect(markup).not.toMatch(/Bearer/i);
    const failing = await render(t, { m: "kd" }, fakeFaceit({ [FACEIT_IDS.vega]: 401, [FACEIT_IDS.nova]: 403 }));
    expect(failing).not.toContain(KEY);
  });
});

describe("page wiring", () => {
  it("pageStartsWithRequireStaffContext", () => {
    const source = readFileSync(join(__dirname, "..", "app", "staff", "(panel)", "statistics", "page.tsx"), "utf8");
    const body = source.slice(source.search(/export default (async )?function/));
    expect(/\bawait\s+([A-Za-z_$][\w$.]*)/.exec(body)?.[1]).toBe("requireStaffContext");
    expect(body).toContain('title="Статистика"');
  });

  it("sidebarLinksStatistics", () => {
    const items = NAV_GROUPS.flatMap((g) => g.items);
    const stats = items.find((i) => i.name === "Статистика");
    expect(stats).toMatchObject({ href: "/staff/statistics", activePath: "/staff/statistics" });
    expect(isActive(stats as (typeof items)[number], "/staff/statistics")).toBe(true);
    expect(isActive(stats as (typeof items)[number], "/staff/overview")).toBe(false);
    // «Игровая статистика» is plan C
    expect(items.find((i) => i.name === "Игровая статистика")).toMatchObject({ href: null, activePath: null });
  });
});
