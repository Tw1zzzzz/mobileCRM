import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import fixture from "./fixtures/staff-metrics.json";
import { EmptyTeam } from "@/components/staff/overview/EmptyTeam";
import { OverviewView } from "@/components/staff/overview/OverviewView";
import { ReadinessBadge } from "@/components/staff/overview/ReadinessBadge";
import { teamOverview, type OverviewData, type PlayerDocuments } from "@/lib/staff/metrics";
import { DEFAULT_SHOW, type OverviewFilters, type ShowKey } from "@/lib/staff/overview";

const TODAY = "2026-10-15";
const TZ = "Europe/Moscow";
const ROSTER = fixture.rosters.main as PlayerDocuments[];
const PLAYERS = [
  { id: "p1", nickname: "alpha" },
  { id: "p2", nickname: "bravo" },
  { id: "p3", nickname: "charlie" },
  { id: "p4", nickname: "delta" },
];
const ALL_SHOWN: readonly ShowKey[] = ["state", "sleep", "food"];

function overviewOf(who: string, docs: readonly PlayerDocuments[] = ROSTER): OverviewData {
  return teamOverview(docs, TODAY, TZ, who);
}

function view(who: string, show: readonly ShowKey[] = ALL_SHOWN, docs: readonly PlayerDocuments[] = ROSTER): string {
  const filters: OverviewFilters = { who, show };
  return renderToStaticMarkup(<OverviewView overview={overviewOf(who, docs)} players={PLAYERS} filters={filters} />);
}

/** Text of the markup without tags, to assert on what a person reads. */
function text(markup: string): string {
  return markup.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ");
}

describe("readiness badge", () => {
  it("overviewShowsTeamReadiness", () => {
    const markup = renderToStaticMarkup(<ReadinessBadge value={overviewOf("team").teamReadiness} />);
    expect(text(markup)).toContain("62");
    expect(text(markup)).toContain("готовность");
    expect(markup).toContain("Готовность команды сегодня: 62 из 100");
  });

  it("readinessWithoutDataSaysNoData", () => {
    const markup = renderToStaticMarkup(<ReadinessBadge value={null} />);
    expect(text(markup)).toContain("нет данных");
    expect(markup).not.toMatch(/\bNaN\b|null/);
  });
});

describe("overview body", () => {
  it("teamViewShowsTeamNumbers", () => {
    const t = text(view("team"));
    expect(t).toContain("Недельный обзор");
    expect(t).toContain("13 записей за 7 дней · вся команда");
    expect(t).toContain("5,5"); // mood average 5.54
    expect(t).toContain("Средне"); // mood pill
    expect(t).toContain("Средний сон 7ч 09м"); // 7.15 h
    expect(t).toContain("Меньше всего сна сб · 6ч 00м"); // 2026-10-10 is a Saturday
    expect(t).toContain("Энергия сегодня 5,0 ↑");
    expect(t).toContain("Готовность 7 дней · вся команда Средне 69 ↑ 4 к прошлой неделе");
    expect(t).toContain("Когнитивная форма");
    expect(t).toContain("Питание, ккал в день");
    expect(t).toContain("1 883");
    expect(t).toContain("↓ 50 к прошлой неделе");
  });

  it("overviewWhoFilterSwitchesToPlayer", () => {
    const team = text(view("team"));
    const player = text(view("p1"));
    expect(player).toContain("11 записей за 7 дней · alpha");
    expect(player).toContain("Сон и энергия 7 дней · alpha");
    expect(player).toContain("6,1"); // p1 mood average 6.09
    expect(player).toContain("Средний сон 7ч 05м");
    expect(player).toContain("Меньше всего сна вт · 5ч 30м"); // 2026-10-13 is a Tuesday
    expect(player).not.toContain("вся команда");
    expect(team).not.toBe(player);
    // the team readiness chip of the header does not follow the filter
    expect(overviewOf("p1").teamReadiness).toBe(overviewOf("team").teamReadiness);
  });

  it("filterChipsAreLinksThatKeepTheOtherFilter", () => {
    const markup = view("p2", ["state"]);
    // switching the player keeps the selected metrics; toggling a metric keeps the player
    expect(markup).toContain('href="/staff/overview?who=p1&amp;show=state"');
    expect(markup).toContain('href="/staff/overview?who=p2&amp;show=state,food"'); // «Питание» on
    expect(markup).toContain('href="/staff/overview?who=p2"'); // «Сон» on gives the default set again
    expect(markup).toContain('href="/staff/overview?who=p2&amp;show="'); // «Состояние» off leaves nothing
    expect(markup).toContain('href="/staff/overview?show=state"'); // «Вся команда» keeps the metrics
  });

  it("selectedChipsAreMarked", () => {
    const markup = view("p2", ["state", "food"]);
    expect(markup).toMatch(/<a[^>]*aria-current="true"[^>]*>Состояние<\/a>/);
    expect(markup).toMatch(/<a[^>]*aria-current="true"[^>]*>Питание<\/a>/);
    expect(markup).not.toMatch(/<a[^>]*aria-current="true"[^>]*>Сон<\/a>/);
    expect(markup).toMatch(/<a[^>]*aria-current="true"[^>]*>bravo<\/a>/);
    expect(markup).not.toMatch(/<a[^>]*aria-current="true"[^>]*>Вся команда<\/a>/);
  });

  it("gameChipIsHiddenUntilPlanC", () => {
    const t = text(view("team", DEFAULT_SHOW));
    expect(t).not.toContain("Игра");
    expect(t).not.toContain("K/D");
    expect(t).not.toContain("Win-rate");
  });

  it("showTogglesBlocks", () => {
    const stateOnly = text(view("team", ["state"]));
    expect(stateOnly).toContain("Настроение");
    expect(stateOnly).toContain("Готовность");
    expect(stateOnly).not.toContain("Сон и энергия");
    expect(stateOnly).not.toContain("Питание, ккал");

    const sleepOnly = text(view("team", ["sleep"]));
    expect(sleepOnly).toContain("Сон и энергия");
    expect(sleepOnly).not.toContain("Настроение");
    expect(sleepOnly).not.toContain("Когнитивная форма");

    const foodOnly = text(view("team", ["food"]));
    expect(foodOnly).toContain("Питание, ккал в день");
    expect(foodOnly).not.toContain("Когнитивная форма");
  });

  it("nothingSelectedAsksToPickAMetric", () => {
    expect(text(view("team", []))).toContain("Выберите хотя бы одну метрику");
  });

  it("blockWithoutDataSaysNoData", () => {
    // p3 has no documents at all
    const markup = view("p3");
    const t = text(markup);
    expect(t).toContain("0 записей за 7 дней · charlie");
    expect((t.match(/нет данных/g) ?? []).length).toBeGreaterThanOrEqual(6);
    expect(t).toContain("Средний сон нет данных");
    expect(t).toContain("Энергия сегодня нет данных");
    expect(t).toContain("Меньше всего сна нет данных");
    expect(markup).not.toMatch(/NaN|undefined|Infinity|>null</);
    expect(markup).not.toMatch(/\bd="[^"]*NaN/);
  });

  it("partialDataDoesNotInventNumbers", () => {
    // p2 syncs no cognitive tests: that card says «нет данных», the rest has numbers
    const t = text(view("p2"));
    expect(t).toContain("Когнитивная форма 7 дней · bravo нет данных");
    expect(t).toContain("Готовность 7 дней · bravo");
  });

  it("noDemoNamesInMarkup", () => {
    const all = [
      view("team"),
      view("p1"),
      view("p3"),
      view("team", []),
      renderToStaticMarkup(<EmptyTeam playerCode="K7M2QX" />),
      renderToStaticMarkup(<ReadinessBadge value={80} />),
    ].join("\n");
    for (const demo of ["Илья Воронов", "Atlant Esports", "Тренировка 12:00", "Atlant", "Матч ESL", "vexa", "m1rage"]) {
      expect(all).not.toContain(demo);
    }
  });

  it("chartsHaveTextAlternatives", () => {
    const markup = view("team");
    expect(markup).toContain('aria-label="Распределение настроения:');
    expect(markup).toContain('aria-label="Сон и энергия по дням:');
    expect(markup).toContain('aria-label="Готовность за неделю:');
  });
});

describe("empty team", () => {
  it("overviewEmptyTeamShowsInviteCode", () => {
    const markup = renderToStaticMarkup(<EmptyTeam playerCode="K7M2QX" />);
    const t = text(markup);
    expect(t).toContain("В команде пока нет игроков");
    expect(t).toContain("K7M2QX");
    expect(t).toContain("Отправьте этот код игрокам");
  });
});
