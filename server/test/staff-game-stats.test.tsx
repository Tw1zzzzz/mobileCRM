import { readFileSync } from "node:fs";
import { join } from "node:path";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { GameForm } from "@/components/staff/game-stats/GameForm";
import { checkDraft } from "@/components/staff/game-stats/check";
import { draftFromGame, newDraft, withCell, type Draft, type RosterPlayer } from "@/components/staff/game-stats/draft";
import { PeriodSummary } from "@/components/staff/game-stats/PeriodSummary";
import type { Game, GameMap, GameSummary } from "@/lib/staff/games";
import { gameTotals } from "@/lib/staff/games-math";
import { GAME_STATS_PATH, parseSummaryParams, summaryHref } from "@/lib/staff/game-summary";
import { buildSummary } from "@/lib/staff/game-summary-table";

const ALPHA: RosterPlayer = { id: "a1111111-1111-4111-8111-111111111111", nickname: "vega" };
const BRAVO: RosterPlayer = { id: "b2222222-2222-4222-8222-222222222222", nickname: "nova" };

function render(roster: readonly RosterPlayer[], initial: Draft | null = null): string {
  return renderToStaticMarkup(<GameForm teamId="team-1" roster={roster} today="2026-11-04" initial={initial} />);
}

const text = (markup: string): string => markup.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ");

/** A draft with everything required filled for both players. */
function filled(): Draft {
  let draft = newDraft([ALPHA, BRAVO], "2026-11-04");
  draft = { ...draft, maps: [{ ...draft.maps[0], map: "mirage", our: "13", their: "9", pistol1: "won", pistol2: "lost" }] };
  for (const player of [ALPHA, BRAVO]) {
    for (const [key, value] of [["kills", "20"], ["deaths", "10"], ["assists", "4"], ["adr", "80,5"]] as const) {
      draft = withCell(draft, 0, player.id, key, value);
    }
  }
  return draft;
}

describe("«Внести игру» form", () => {
  it("formShowsRosterRows", () => {
    const markup = render([ALPHA, BRAVO]);
    const t = text(markup);
    for (const heading of ["Игра", "Карты и счёт", "Статистика игроков · карта 1", "Итог игры · считается сам", "Перед сохранением"]) {
      expect(t).toContain(heading);
    }
    expect(t).toContain("Команда · 2");
    expect(t).toContain("Пракк");
    expect(t).toContain("Турнир");
    expect(t).not.toContain("Faceit");
    // A line per player of the team, with every input of the table.
    expect(markup).toMatch(/<span role="rowheader"[^>]*><span[^>]*>vega<\/span>/);
    expect(markup).toMatch(/<span role="rowheader"[^>]*><span[^>]*>nova<\/span>/);
    for (const title of ["KAST, %", "Utility damage per round", "Убийства", "Смерти", "Ассисты", "Средний урон за раунд", "First Kills", "First Deaths", "Ср. мультикиллы", "Выигранные клатчи", "Ср. время ослепления, с"]) {
      expect(markup).toContain(`aria-label="vega, ${title}"`);
      expect(markup).toContain(`aria-label="nova, ${title}"`);
    }
    for (const column of ["K/D", "KPR", "DPR", "Опен ±"]) expect(t).toContain(column);
    expect(markup).toContain('value="2026-11-04"');
    expect(t).toContain("Сохранить игру");
    expect(t).toContain("Черновик сохраняется автоматически");
    expect(t).toContain("Очистить");
    expect(t).toContain("Добавить запасного");
    expect(t).toContain("Строки из Excel или Google Таблиц можно вставить в любую ячейку — Ctrl+V");
  });

  it("checklist lists missing required cells", () => {
    const t = text(render([ALPHA, BRAVO]));
    expect(t).toContain("Карта 1: выберите карту");
    expect(t).toContain("Карта 1: укажите счёт");
    expect(t).toContain("Карта 1: отметьте оба пистолетных раунда");
    // Two players x K, D, A, ADR.
    expect(t).toContain("Карта 1: не заполнено 8 обязательных ячеек");
    expect(t).not.toContain("Всё обязательное заполнено");
    expect(text(render([ALPHA]))).toContain("Карта 1: не заполнено 4 обязательные ячейки");
  });

  it("aFilledGameHasNothingLeftToFix", () => {
    const draft = filled();
    expect(checkDraft(draft).issues).toEqual([]);
    const t = text(render([ALPHA, BRAVO], draft));
    expect(t).toContain("Всё обязательное заполнено");
    expect(t).toContain("Необязательные метрики: пусто 14 ячеек. Игру можно сохранить и дополнить позже.");
    // The live block: 13:9 is a win, 22 rounds, no draw.
    expect(t).toContain("1 : 0");
    expect(t).toContain("по картам · 1 карта (П : П)");
    expect(t).toContain("100% ");
    expect(t).toContain("13–9");
    expect(t).toContain("K/D команды 2,00");
  });

  it("anEditedGameKeepsNoDraftNote", () => {
    const t = text(render([ALPHA, BRAVO], { ...filled(), id: "c3333333-3333-4333-8333-333333333333" }));
    expect(t).toContain("Сохранить игру");
    expect(t).not.toContain("Черновик сохраняется автоматически");
    expect(t).not.toContain("Очистить");
  });

  it("noDemoNames", () => {
    const markup = render([ALPHA, BRAVO]);
    for (const demo of ["NICKNAME12345", "Игрок 2", "Игрок 3", "Team Alpha", "Team Bravo", "Atlant", "Coach", "Natus"]) {
      expect(markup).not.toContain(demo);
    }
    // The opponent starts empty; nothing is typed into the table.
    expect(markup).toMatch(/<input id="gs-opp"[^>]*value=""/);
    expect(markup).not.toMatch(/aria-label="vega, Убийства"[^>]*value="[^"]/);
  });

  it("aTeamWithoutPlayersAsksForStandIns", () => {
    const markup = render([]);
    const t = text(markup);
    expect(t).toContain("Команда · 0");
    expect(t).toContain("Карта 1: добавьте хотя бы одного игрока");
    expect(markup).toMatch(/<button[^>]*disabled[^>]*>Один игрок<\/button>/);
  });
});

describe("page wiring", () => {
  it("pageStartsWithRequireStaffContext", () => {
    const source = readFileSync(join(__dirname, "..", "app", "staff", "(panel)", "game-stats", "page.tsx"), "utf8");
    const body = source.slice(source.search(/export default (async )?function/));
    expect(/\bawait\s+([A-Za-z_$][\w$.]*)/.exec(body)?.[1]).toBe("requireStaffContext");
    expect(body).toContain('title="Игровая статистика"');
    expect(body).toContain("PeriodSummary");
  });

  it("actionsStartWithRequireStaffContext", () => {
    const source = readFileSync(join(__dirname, "..", "app", "staff", "actions.ts"), "utf8");
    for (const name of ["saveGameAction", "deleteGameAction"]) {
      const body = source.slice(source.indexOf(`export async function ${name}`));
      expect(/\bawait\s+([A-Za-z_$][\w$.]*)/.exec(body)?.[1]).toBe("requireStaffContext");
    }
  });
});

// ------------------------------------------------------------------------------------------ «Сводка за период»

const FIXED_TODAY = "2026-11-04";
const ROSTER_IDS = [ALPHA.id, BRAVO.id];
const noParams = {};

describe("period and filters in the URL", () => {
  it("defaultsToTheLast30DaysOfEveryone", () => {
    const params = parseSummaryParams(noParams, ROSTER_IDS, FIXED_TODAY);
    expect(params).toMatchObject({ period: 30, kind: "all", who: "team", range: { from: "2026-10-06", to: FIXED_TODAY } });
    expect(summaryHref(params)).toBe(`${GAME_STATS_PATH}#gs-period`);
  });

  it("presetsAreSevenThirtyAndNinetyDays", () => {
    expect(parseSummaryParams({ period: "7" }, ROSTER_IDS, FIXED_TODAY).range).toEqual({ from: "2026-10-29", to: FIXED_TODAY });
    expect(parseSummaryParams({ period: "90" }, ROSTER_IDS, FIXED_TODAY).range.from).toBe("2026-08-07");
    expect(parseSummaryParams({ period: "365" }, ROSTER_IDS, FIXED_TODAY).period).toBe(30);
  });

  it("customPeriod", () => {
    const params = parseSummaryParams({ period: "custom", from: "2026-09-01", to: "2026-09-30" }, ROSTER_IDS, FIXED_TODAY);
    expect(params).toMatchObject({ period: "custom", range: { from: "2026-09-01", to: "2026-09-30" } });
    expect(summaryHref(params)).toBe(`${GAME_STATS_PATH}?period=custom&from=2026-09-01&to=2026-09-30#gs-period`);
    // A one-day period is fine; a backwards, impossible or year-long-plus range is the last 30 days instead.
    expect(parseSummaryParams({ period: "custom", from: "2026-09-05", to: "2026-09-05" }, ROSTER_IDS, FIXED_TODAY).range).toEqual({ from: "2026-09-05", to: "2026-09-05" });
    for (const bad of [
      { from: "2026-09-30", to: "2026-09-01" },
      { from: "2026-02-30", to: "2026-03-01" },
      { from: "2024-01-01", to: "2026-01-01" },
      { from: "yesterday", to: "today" },
      {},
    ]) {
      expect(parseSummaryParams({ period: "custom", ...bad }, ROSTER_IDS, FIXED_TODAY).range).toEqual({ from: "2026-10-06", to: FIXED_TODAY });
    }
  });

  it("sourceAndPlayerLiveInTheUrl", () => {
    const params = parseSummaryParams({ kind: "tournament", who: BRAVO.id, period: "90" }, ROSTER_IDS, FIXED_TODAY);
    expect(params).toMatchObject({ kind: "tournament", who: BRAVO.id, period: 90 });
    expect(summaryHref(params)).toBe(`${GAME_STATS_PATH}?period=90&kind=tournament&who=${BRAVO.id}#gs-period`);
    expect(summaryHref(params, { kind: "all", who: "team" })).toBe(`${GAME_STATS_PATH}?period=90#gs-period`);
    // Someone who is not on the roster, or a source that does not exist, is «everyone» / «all», without an error.
    const stranger = parseSummaryParams({ who: "c3333333-3333-4333-8333-333333333333", kind: "faceit" }, ROSTER_IDS, FIXED_TODAY);
    expect(stranger).toMatchObject({ who: "team", kind: "all" });
  });
});

function game(over: Partial<GameSummary> & { id: string }, ours = 13, theirs = 9, who?: string): GameSummary {
  const maps: GameMap[] = [{
    id: `${over.id}-m`, position: 0, map: "mirage", ourScore: ours, theirScore: theirs, pistol1: "won", pistol2: "lost",
    rows: [{
      id: `${over.id}-r1`, playerId: ALPHA.id, nickname: "vega", kills: 20, deaths: 10, assists: 4, adr: 80, fk: 3, fd: 1,
      kast: 75, udr: 12.5, multikills: 1, clutches: 2, flashTime: 2.5,
    }, {
      id: `${over.id}-r2`, playerId: BRAVO.id, nickname: "nova", kills: 10, deaths: 10, assists: 2, adr: 60, fk: null, fd: null,
      kast: null, udr: null, multikills: null, clutches: null, flashTime: null,
    }],
  }];
  const base = {
    kind: "pracc" as const, playedOn: "2026-11-01", opponent: null, createdBy: null, createdAt: new Date(0), updatedAt: new Date(0),
    maps,
  };
  // As `listGames` does: the totals of the chosen player only.
  return { ...base, ...over, totals: gameTotals(over.maps ?? maps, who) };
}

const GAME_A = game({ id: "d4444444-4444-4444-8444-444444444444", playedOn: "2026-11-02", opponent: "Natus Vincere", kind: "tournament" });
const GAME_B = game({ id: "e5555555-5555-4555-8555-555555555555", playedOn: "2026-10-20" }, 5, 13);

function renderSummary(games: readonly GameSummary[], over: Partial<{ who: string; confirm: Game | null }> = {}): string {
  const params = parseSummaryParams({}, ROSTER_IDS, FIXED_TODAY);
  return renderToStaticMarkup(
    <PeriodSummary params={{ ...params, who: over.who ?? "team" }} roster={[ALPHA, BRAVO]} games={games} confirm={over.confirm ?? null} />,
  );
}

describe("«Сводка за период» numbers", () => {
  it("summaryTotalsFromGames", () => {
    const summary = buildSummary([GAME_A, GAME_B], "team");
    expect(Object.fromEntries(summary.tiles.map((t) => [t.label, t.value]))).toEqual({
      "Игр за период": "2",
      "K/D": "1,50",
      ADR: "70",
      "Win Rate по картам": "50%",
    });
    // Columns: the period, then the games newest first as listed.
    expect(summary.columns.map((c) => c.title)).toEqual(["За период", "02.11 · Турнир", "20.10 · Пракк"]);
    expect(summary.columns[0].sub).toBe("2 игры");
    expect(summary.columns[1].sub).toBe("vs Natus Vincere · Mirage");
    const row = (label: string) => summary.rows.find((r) => r.kind === "data" && r.label === label);
    expect(row("Убийства (Kills)")).toMatchObject({ cells: ["60", "30", "30"] });
    expect(row("K/D Ratio")).toMatchObject({ cells: ["1,50", "1,50", "1,50"] });
    expect(row("Карты (П–П–Н)")).toMatchObject({ cells: ["1–1–0", "1–0–0", "0–1–0"] });
    expect(row("Win Rate")).toMatchObject({ cells: ["50%", "100%", "0%"] });
    expect(row("Раунды (выиграно / всего)")).toMatchObject({ cells: ["18 / 40", "13 / 22", "5 / 18"] });
    expect(row("AVG Rounds Won")).toMatchObject({ cells: ["9", "13", "5"] });
    expect(row("Пистолетные (выиграно / всего)")).toMatchObject({ cells: ["2 / 4", "1 / 2", "1 / 2"] });
    expect(row("ADR")).toMatchObject({ cells: ["70", "70", "70"] });
    expect(row("KAST")).toMatchObject({ cells: ["75%", "75%", "75%"] });
    expect(row("UDR")).toMatchObject({ cells: ["12,5", "12,5", "12,5"] });
    expect(row("Разница опен-дуэлей")).toMatchObject({ cells: ["+4", "+2", "+2"] });
    expect(row("Выигранные клатчи")).toMatchObject({ cells: ["4", "2", "2"] });
    expect(row("Ср. время ослепления, с")).toMatchObject({ cells: ["2,5", "2,5", "2,5"] });
  });

  it("aPlayerOnlyCountsTheirOwnLines", () => {
    const summary = buildSummary([game({ id: GAME_A.id, playedOn: GAME_A.playedOn, kind: "tournament" }, 13, 9, BRAVO.id)], BRAVO.id);
    const row = (label: string) => summary.rows.find((r) => r.kind === "data" && r.label === label);
    expect(row("Убийства (Kills)")).toMatchObject({ cells: ["10", "10"] });
    expect(row("KAST")).toMatchObject({ cells: ["—", "—"] });
    expect(row("First Kills")).toMatchObject({ cells: ["—", "—"] });
    expect(row("Разница опен-дуэлей")).toMatchObject({ cells: ["—", "—"] });
  });

  it("noGamesMeansNoColumnsAndNoInventedNumbers", () => {
    const summary = buildSummary([], "team");
    expect(summary.columns).toEqual([]);
    expect(summary.rows).toEqual([]);
    expect(Object.fromEntries(summary.tiles.map((t) => [t.label, t.value]))).toEqual({
      "Игр за период": "0",
      "K/D": "нет данных",
      ADR: "нет данных",
      "Win Rate по картам": "нет данных",
    });
  });
});

describe("«Сводка за период» markup", () => {
  it("showsTheBoardsControlsAndTiles", () => {
    const markup = renderSummary([GAME_A, GAME_B]);
    const t = text(markup);
    expect(t).toContain("Сводка за период");
    expect(t).toContain("Итог за период и каждая игра отдельно — новые слева");
    for (const label of ["7 дней", "30 дней", "90 дней", "Свой период", "Все", "Пракки", "Турниры", "Команда", "vega", "nova"]) expect(t).toContain(label);
    expect(t).not.toContain("Faceit");
    for (const label of ["Игр за период", "K/D", "ADR", "Win Rate по картам"]) expect(t).toContain(label);
    expect(markup).toMatch(/aria-label="Период"/);
    expect(markup).toMatch(/aria-label="Источник"/);
    expect(markup).toMatch(/aria-label="Игрок"/);
    // 30 days and everybody are the ones switched on.
    expect(markup).toMatch(/<a[^>]*aria-current="true"[^>]*>30 дней<\/a>/);
    expect(markup).toMatch(/<a[^>]*aria-current="true"[^>]*>Все<\/a>/);
    expect(markup).toMatch(/<a[^>]*aria-current="true"[^>]*>Команда<\/a>/);
    for (const group of ["Убийства (Kills)", "K/D Ratio", "Аналитика", "Карты и раунды", "Pistol Win Rate"]) expect(t).toContain(group);
    expect(t).toContain("02.11 · Турнир");
    expect(t).toContain("20.10 · Пракк");
  });

  it("emptyFilterMessage", () => {
    const t = text(renderSummary([]));
    expect(t).toContain("Нет игр для этого фильтра");
    expect(t).toContain("Выберите другой источник или игрока");
    expect(t).not.toContain("Показатель");
  });

  it("customPeriodOpensTwoDates", () => {
    const params = parseSummaryParams({ period: "custom", from: "2026-09-01", to: "2026-09-30" }, ROSTER_IDS, FIXED_TODAY);
    const markup = renderToStaticMarkup(<PeriodSummary params={params} roster={[ALPHA]} games={[]} confirm={null} />);
    expect(markup).toMatch(/<input[^>]*type="date"[^>]*name="from"[^>]*value="2026-09-01"/);
    expect(markup).toMatch(/<input[^>]*type="date"[^>]*name="to"[^>]*value="2026-09-30"/);
    expect(markup).toContain('name="period" value="custom"');
  });

  it("editOpensForm", () => {
    const markup = renderSummary([GAME_A, GAME_B]);
    expect(markup).toContain(`href="/staff/game-stats?edit=${GAME_A.id}"`);
    expect(markup).toContain(`href="/staff/game-stats?edit=${GAME_B.id}"`);
    expect(markup).toMatch(/aria-label="Изменить игру 02\.11"/);
    // The form itself opens the saved game: no draft note, the saved id kept.
    const draft = draftFromGame(GAME_A, [ALPHA, BRAVO]);
    expect(draft.id).toBe(GAME_A.id);
    expect(draft.opponent).toBe("Natus Vincere");
    expect(text(render([ALPHA, BRAVO], draft))).not.toContain("Черновик сохраняется автоматически");
  });

  it("deleteGame", () => {
    // First a link to the confirmation, no deleting yet.
    const asking = renderSummary([GAME_A]);
    expect(asking).toContain(`href="/staff/game-stats?delete=${GAME_A.id}#confirm-delete"`);
    expect(asking).not.toContain("Она пропадёт из статистики команды");
    // Then the confirmation: the question, a form posting the id, and a way back that keeps the filters.
    const markup = renderSummary([GAME_A], { confirm: GAME_A });
    expect(text(markup)).toContain("Удалить игру? Она пропадёт из статистики команды");
    expect(markup).toMatch(/<form[^>]*>\s*<input type="hidden" name="id" value="d4444444-4444-4444-8444-444444444444"\/>/);
    expect(markup).toMatch(/<button[^>]*type="submit"[^>]*>Удалить<\/button>/);
    expect(markup).toMatch(/<a[^>]*href="\/staff\/game-stats#gs-period"[^>]*>Отмена<\/a>/);
  });
});
