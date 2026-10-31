import { renderToStaticMarkup } from "react-dom/server";
import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CardFrame } from "@/components/staff/card/CardFrame";
import { mapMatch } from "../lib/faceit/mapper";
import { loadCard, type CardData } from "../lib/staff/card";
import { gameSummary } from "../lib/staff/game";
import { resetDb, testDeps } from "./helpers";
import { putDoc, seedCardTeam, type CardTeam } from "./staff-card-fixture";

const deps = testDeps();
const TZ = "Europe/Moscow";
const KEY = "test-faceit-key";

const FACEIT_IDS = {
  vega: "aaaaaaaa-0000-4000-8000-00000000000a",
  nova: "aaaaaaaa-0000-4000-8000-00000000000b",
  outsider: "aaaaaaaa-0000-4000-8000-0000000000ff",
};

beforeEach(async () => {
  await resetDb(deps.sql);
  vi.stubEnv("FACEIT_API_KEY", KEY);
});
afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});
afterAll(() => deps.sql.end());

/** Text of the markup without tags. */
function text(markup: string): string {
  return markup.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim();
}

function card(data: CardData): Extract<CardData, { kind: "card" }> {
  if (data.kind !== "card") throw new Error("expected a card");
  return data;
}

// ---------------------------------------------------------------- a fake Faceit

const ms = (iso: string): number => Date.parse(iso);

/** One raw Faceit item; `stats` overrides are merged over a plain win. */
function item(finishedAt: string, stats: Record<string, unknown> = {}): unknown {
  return {
    stats: {
      Result: "1", Score: "13 / 9", Kills: "20", Deaths: "10", Assists: "4", ADR: "80", Rounds: "22",
      "Match Finished At": ms(finishedAt), ...stats,
    },
  };
}

interface FakeFaceit {
  http: typeof fetch;
  /** Every requested URL. */
  calls: string[];
}

/** Answers `GET .../players/{id}/games/cs2/stats` by player id: an array of items, or an HTTP status number. */
function fakeFaceit(byPlayer: Record<string, unknown[] | number>): FakeFaceit {
  const calls: string[] = [];
  const http = (async (input: unknown) => {
    const url = String(input);
    calls.push(url);
    const id = url.match(/\/players\/([^/]+)\/games\/cs2\/stats/)?.[1];
    const answer = id === undefined ? undefined : byPlayer[id];
    if (answer === undefined) return new Response("{}", { status: 404 });
    if (typeof answer === "number") return new Response("{}", { status: answer });
    return new Response(JSON.stringify({ items: answer }), { status: 200, headers: { "content-type": "application/json" } });
  }) as typeof fetch;
  return { http, calls };
}

async function connect(userId: string, playerId: string | null, nickname = "vega_f"): Promise<void> {
  await putDoc(deps, userId, "faceit", { nickname, playerId, snapshots: [] });
}

async function open(t: CardTeam, query: Record<string, string>, faceit: FakeFaceit | null = null) {
  const data = card(await loadCard(deps, t.ctx, query, TZ, faceit ? { http: faceit.http, apiKey: KEY } : {}));
  return { data, markup: renderToStaticMarkup(<CardFrame card={data} />) };
}

interface Group {
  title: string;
  tiles: [string, string][];
}

/** The «Игра» tab's groups: `<h3>` title, then `<dt>`/`<dd>` pairs. */
function groupsOf(markup: string): Group[] {
  return markup
    .split(/<h3[^>]*>/)
    .slice(1)
    .map((part) => ({
      title: part.slice(0, part.indexOf("</h3>")),
      tiles: [...part.matchAll(/<dt[^>]*>([^<]*)<\/dt><dd[^>]*>([^<]*)<\/dd>/g)].map((m) => [m[1], m[2]] as [string, string]),
    }));
}

function tileMap(markup: string): Record<string, string> {
  return Object.fromEntries(groupsOf(markup).flatMap((g) => g.tiles));
}

// ---------------------------------------------------------------- «Игра»

/**
 * Three matches in the 30-day period 27 Sep - 26 Oct (Moscow) and one before it.
 *  M1 win 13/9: 24 kills, 12 deaths, 5 assists, ADR 90, 22 rounds; first kills 4, entries 5 (3 won), 1v1 wins 1, 2K 2, 3K 1, utility 110.
 *  M2 loss 7/13: 10/20/2, ADR 60, 20 rounds; first kills 1, entries 4 (1 won), 1v1 wins 0, 2K 0, utility 50.
 *  M3 win 13/11: 15/15/3, ADR 70, 24 rounds; no detail fields.
 */
const GAME_ITEMS: unknown[] = [
  item("2026-10-24T10:00:00Z", { Kills: "15", Deaths: "15", Assists: "3", ADR: "70", Rounds: "24", Score: "13 / 11" }),
  item("2026-10-22T10:00:00Z", {
    Result: "0", Score: "7 / 13", Kills: "10", Deaths: "20", Assists: "2", ADR: "60", Rounds: "20",
    "First Kills": "1", "Entry Count": "4", "Entry Wins": "1", "1v1Wins": "0", "1v2Wins": "0", "Double Kills": "0", "Utility Damage": "50",
  }),
  item("2026-10-20T10:00:00Z", {
    Kills: "24", Deaths: "12", Assists: "5", ADR: "90", Rounds: "22", "First Kills": "4", "Entry Count": "5", "Entry Wins": "3",
    "1v1Wins": "1", "1v2Wins": "0", "Double Kills": "2", "Triple Kills": "1", "Utility Damage": "110",
  }),
  item("2026-08-01T10:00:00Z", { Kills: "99", Deaths: "1", ADR: "150" }),
];

describe("«Игра» tab", () => {
  it("gameTilesFromMatches", async () => {
    const t = await seedCardTeam(deps);
    await connect(t.ids.vega, FACEIT_IDS.vega);
    const faceit = fakeFaceit({ [FACEIT_IDS.vega]: GAME_ITEMS });
    const { markup } = await open(t, { player: t.ids.vega, tab: "game" }, faceit);

    const groups = groupsOf(markup);
    expect(groups.map((g) => g.title)).toEqual(["K/D", "Аналитические метрики", "Матчи, раунды, пистолетки"]);
    expect(groups[0].tiles.map(([label]) => label)).toEqual(["Убийства", "Смерти", "Ассисты", "K/D"]);
    expect(groups[1].tiles.map(([label]) => label)).toEqual([
      "ADR", "KPR", "Death/round", "AVG KR", "AVG KD", "KAST", "First kills", "First deaths", "UDR",
      "Разница опен-дуэлей", "Ср. мультикиллы", "Выигранные клатчи", "Ср. время ослепления",
    ]);
    expect(groups[2].tiles.map(([label]) => label)).toEqual([
      "Всего матчей", "Победы", "Поражения", "Ничьи", "Win-Rate", "Раунды выиграно / всего", "Round Win-Rate",
      "AVG Rounds Won", "AVG Rounds Lost", "Пистолетные выиграно / всего", "Pistol Win-Rate",
    ]);

    // 49 kills, 47 deaths, 10 assists over 66 rounds; the match before the period is not counted
    const tiles = tileMap(markup);
    expect(tiles).toMatchObject({
      "Убийства": "49", "Смерти": "47", "Ассисты": "10", "K/D": "1.04",
      "ADR": "73.3", "KPR": "0.74", "Death/round": "0.71", "AVG KR": "0.74", "AVG KD": "1.17",
      "First kills": "5", "UDR": "3.8", "Ср. мультикиллы": "1.5", "Выигранные клатчи": "1",
      "Всего матчей": "3", "Победы": "2", "Поражения": "1", "Win-Rate": "67%",
      "Раунды выиграно / всего": "33 / 66", "Round Win-Rate": "50%", "AVG Rounds Won": "11.0", "AVG Rounds Lost": "11.0",
    });
    expect(faceit.calls).toEqual([`https://open.faceit.com/data/v4/players/${FACEIT_IDS.vega}/games/cs2/stats?limit=100`]);
  });

  it("openDuelDiffIsEntryWinsMinusLossesNeverFirstKills", async () => {
    const t = await seedCardTeam(deps);
    await connect(t.ids.vega, FACEIT_IDS.vega);
    const { data, markup } = await open(t, { player: t.ids.vega, tab: "game" }, fakeFaceit({ [FACEIT_IDS.vega]: GAME_ITEMS }));
    // entries: M1 +1 (3 won of 5), M2 -2 (1 won of 4) -> -1; first kills 4 + 1 = 5 would say +5 or similar
    expect(tileMap(markup)["Разница опен-дуэлей"]).toBe("−1");
    expect(data.game).not.toBeNull();
    const mapped = GAME_ITEMS.map((raw) => mapMatch(raw)).filter((m) => m !== null);
    expect(gameSummary(mapped, "2026-09-27", "2026-10-26", TZ).openDuelDiff).toBe(-1);

    // a positive and a zero difference are signed the board's way
    const plus = [item("2026-10-24T10:00:00Z", { "Entry Count": "4", "Entry Wins": "3", "First Kills": "0" })];
    const evenly = [item("2026-10-24T10:00:00Z", { "Entry Count": "4", "Entry Wins": "2" })];
    await deps.sql`delete from faceit_cache`;
    const a = await open(t, { player: t.ids.vega, tab: "game" }, fakeFaceit({ [FACEIT_IDS.vega]: plus }));
    expect(tileMap(a.markup)["Разница опен-дуэлей"]).toBe("+2");
    await deps.sql`delete from faceit_cache`;
    const b = await open(t, { player: t.ids.vega, tab: "game" }, fakeFaceit({ [FACEIT_IDS.vega]: evenly }));
    expect(tileMap(b.markup)["Разница опен-дуэлей"]).toBe("±0");
  });

  it("unavailableTilesSayNoData", async () => {
    const t = await seedCardTeam(deps);
    await connect(t.ids.vega, FACEIT_IDS.vega);
    const { markup } = await open(t, { player: t.ids.vega, tab: "game" }, fakeFaceit({ [FACEIT_IDS.vega]: GAME_ITEMS }));
    const tiles = tileMap(markup);
    for (const label of ["KAST", "First deaths", "Ср. время ослепления", "Ничьи", "Пистолетные выиграно / всего", "Pistol Win-Rate"]) {
      expect(tiles[label], label).toBe("нет данных");
    }

    // matches without any detail field: the optional tiles are missing, never zero
    await deps.sql`delete from faceit_cache`;
    const plain = [item("2026-10-24T10:00:00Z"), item("2026-10-23T10:00:00Z", { Result: "0", Score: "10 / 13" })];
    const bare = tileMap((await open(t, { player: t.ids.vega, tab: "game" }, fakeFaceit({ [FACEIT_IDS.vega]: plain }))).markup);
    for (const label of ["First kills", "UDR", "Разница опен-дуэлей", "Ср. мультикиллы", "Выигранные клатчи"]) {
      expect(bare[label], label).toBe("нет данных");
    }
    expect(bare["Всего матчей"]).toBe("2");

    // a period without matches: the counts are real, every ratio is missing
    await deps.sql`delete from faceit_cache`;
    const none = await open(t, { player: t.ids.vega, tab: "game" }, fakeFaceit({ [FACEIT_IDS.vega]: [item("2026-01-05T10:00:00Z")] }));
    const empty = tileMap(none.markup);
    expect(empty["K/D"]).toBe("нет данных");
    expect(empty["Убийства"]).toBe("нет данных");
    expect(empty["Win-Rate"]).toBe("нет данных");
    expect(text(none.markup)).toContain("В выбранном периоде матчей Faceit нет");
    expect(empty["Всего матчей"]).toBe("0"); // a real count: Faceit answered, there were no matches
    expect(Object.values(empty).filter((v) => /NaN|Infinity|^0$/.test(v))).toEqual(["0"]);
  });

  it("disabledSourcesAreNotLinks", async () => {
    const t = await seedCardTeam(deps);
    await connect(t.ids.vega, FACEIT_IDS.vega);
    const faceit = fakeFaceit({ [FACEIT_IDS.vega]: GAME_ITEMS });
    const { markup } = await open(t, { player: t.ids.vega, tab: "game", period: "7", src: "faceit" }, faceit);

    expect(markup).toContain('<label for="f-src"');
    const select = markup.slice(markup.indexOf('id="f-src"'), markup.indexOf("</select>", markup.indexOf('id="f-src"')));
    const options = [...select.matchAll(/<option value="([^"]*)"([^>]*)>([^<]*)<\/option>/g)].map((m) => ({
      value: m[1], label: m[3], disabled: m[2].includes("disabled"), selected: m[2].includes("selected"),
    }));
    expect(options).toEqual([
      { value: "all", label: "Все", disabled: false, selected: false },
      { value: "faceit", label: "Faceit", disabled: false, selected: true },
      { value: "prak", label: "Пракки (скоро)", disabled: true, selected: false },
      { value: "tour", label: "Турниры (скоро)", disabled: true, selected: false },
    ]);
    expect(markup).not.toMatch(/href="[^"]*src=(prak|tour)/);
    // the form carries the rest of the selection but not the source itself
    const form = markup.slice(markup.lastIndexOf("<form", markup.indexOf('id="f-src"')), markup.indexOf('id="f-src"'));
    const hidden = [...form.matchAll(/<input type="hidden" name="([^"]*)" value="([^"]*)"/g)].map((m) => [m[1], m[2]]);
    expect(hidden).toEqual([["player", t.ids.vega], ["period", "7"], ["tab", "game"]]);

    // the board shows both filters on every tab, side by side
    for (const tab of ["profile", "tests", "sleep"]) {
      const other = (await open(t, { player: t.ids.vega, tab })).markup;
      expect(other, tab).toContain('id="f-src"');
      expect(other, tab).toContain('id="f-type"');
    }

    // a URL naming a source that is not built falls back to «Все»
    const fallback = await open(t, { player: t.ids.vega, tab: "game", src: "prak" }, faceit);
    expect(fallback.data.params.src).toBe("all");
    expect(fallback.markup).toMatch(/<option value="all"[^>]*selected/);
  });

  it("sourceFaceitAndAllShowTheSameNumbers", async () => {
    const t = await seedCardTeam(deps);
    await connect(t.ids.vega, FACEIT_IDS.vega);
    const faceit = fakeFaceit({ [FACEIT_IDS.vega]: GAME_ITEMS });
    const all = tileMap((await open(t, { player: t.ids.vega, tab: "game" }, faceit)).markup);
    const only = tileMap((await open(t, { player: t.ids.vega, tab: "game", src: "faceit" }, faceit)).markup);
    expect(only).toEqual(all);
    expect(faceit.calls).toHaveLength(1); // the second view comes from the 15-minute cache
  });
});

// ---------------------------------------------------------------- «Корреляции»

/**
 * Four days 20-23 Oct. Sleep 6, 7, 8, 9 h; mood 5 every day (no spread); energy on two days only; no screen time;
 * cognitive scores 50, 40, 45, 20. Matches (13:00 Moscow): K/D 1.0 1.2 1.4 1.6, ADR 90 70 80 60, loss loss win win.
 */
async function seedCorrelationState(userId: string, sleepHours: number[]): Promise<void> {
  const days = ["20", "21", "22", "23"];
  await putDoc(deps, userId, "sleep", {
    sessions: days.map((d, i) => ({
      id: `s${d}`,
      start: new Date(ms(`2026-10-${d}T04:00:00Z`) - sleepHours[i] * 3_600_000).toISOString(),
      end: `2026-10-${d}T04:00:00Z`,
    })),
  });
  await putDoc(deps, userId, "wellbeing", {
    mood: days.map((d, i) => ({ id: `m${d}`, day: `2026-10-${d}T08:00:00Z`, mood: 5, energy: 6, ...(i > 1 && { energy: undefined }) })),
  });
  await putDoc(deps, userId, "cognitive", {
    results: days.map((d, i) => ({ id: `c${d}`, kind: "reaction", date: `2026-10-${d}T08:00:00Z`, score: [50, 40, 45, 20][i], metric: "x" })),
  });
}

const CORR_ITEMS: unknown[] = [
  item("2026-10-23T10:00:00Z", { Kills: "16", Deaths: "10", ADR: "60" }),
  item("2026-10-22T10:00:00Z", { Kills: "14", Deaths: "10", ADR: "80" }),
  item("2026-10-21T10:00:00Z", { Kills: "12", Deaths: "10", ADR: "70", Result: "0", Score: "9 / 13" }),
  item("2026-10-20T10:00:00Z", { Kills: "10", Deaths: "10", ADR: "90", Result: "0", Score: "7 / 13" }),
];

interface Cell {
  text: string;
  background: string | null;
}

function matrixOf(markup: string): { head: string[]; rows: { name: string; cells: Cell[] }[] } {
  const head = [...markup.matchAll(/role="columnheader"[^>]*>([^<]+)</g)].map((m) => m[1]);
  const rows = markup
    .split('role="row"')
    .filter((part) => part.includes('role="rowheader"'))
    .map((part) => ({
      name: part.match(/role="rowheader"[^>]*>([^<]*)</)?.[1] ?? "",
      cells: [...part.matchAll(/role="cell"([^>]*)>([^<]*)</g)].map((m) => ({
        text: m[2],
        background: m[1].match(/style="background:([^"]*)"/)?.[1] ?? null,
      })),
    }));
  return { head, rows };
}

describe("«Корреляции» tab", () => {
  async function corrTab(sleepHours: number[], query: Record<string, string> = {}) {
    const t = await seedCardTeam(deps);
    await connect(t.ids.vega, FACEIT_IDS.vega);
    await seedCorrelationState(t.ids.vega, sleepHours);
    const faceit = fakeFaceit({ [FACEIT_IDS.vega]: CORR_ITEMS });
    return { t, ...(await open(t, { player: t.ids.vega, tab: "corr", ...query }, faceit)) };
  }

  it("corrCellsAndColours", async () => {
    const { markup } = await corrTab([6, 7, 8, 9]);
    expect(text(markup)).toContain("Как состояние игрока связано с игрой за период. Голубой — прямая связь, оранжевый — обратная, ярче — сильнее");
    expect(markup).toContain('aria-label="Таблица корреляций"');
    const { head, rows } = matrixOf(markup);
    expect(head).toEqual(["K/D", "ADR", "Win Rate"]);
    expect(rows.map((r) => r.name)).toEqual(["Сон", "Настроение", "Энергия", "Экранное время", "Когнитивный индекс"]);
    expect(rows.every((r) => r.cells.length === 3)).toBe(true);

    const [sleep, mood, energy, screen, cog] = rows.map((r) => r.cells);
    // sleep vs K/D is a straight line, vs ADR -0.80, vs win rate +0.89 (hand-computed Pearson)
    expect(sleep[0]).toEqual({ text: "+1.00", background: "rgba(0,140,255,0.82)" });
    expect(sleep[1]).toEqual({ text: "−0.80", background: "rgba(255,122,61,0.68)" });
    expect(sleep[2]).toEqual({ text: "+0.89", background: "rgba(0,140,255,0.75)" });
    expect(cog.map((c) => c.text)).toEqual(["−0.83", "+0.93", "−0.55"]);
    expect(cog[2].background).toBe("rgba(255,122,61,0.50)");
    // constant mood, two days of energy and no screen time: not enough to say anything, and no colour
    for (const row of [mood, energy, screen]) {
      expect(row, "row").toEqual([
        { text: "мало данных", background: null },
        { text: "мало данных", background: null },
        { text: "мало данных", background: null },
      ]);
    }
    expect(text(markup)).not.toContain("KAST");
    expect(markup).not.toMatch(/>[+−]0\.00</);
  });

  it("corrFooterNamesStrongestPair", async () => {
    const up = await corrTab([6, 7, 8, 9]);
    expect(text(up.markup)).toContain(
      "Главное: сильнее всего связаны «Сон» и «K/D» (r = +1.00). Когда первое выше обычного, второе тоже растёт.",
    );
  });

  it("corrFooterSaysFallsForAnInverseLink", async () => {
    const down = await corrTab([9, 8, 7, 6]);
    expect(text(down.markup)).toContain(
      "Главное: сильнее всего связаны «Сон» и «K/D» (r = −1.00). Когда первое выше обычного, второе снижается.",
    );
    expect(matrixOf(down.markup).rows[0].cells[0]).toEqual({ text: "−1.00", background: "rgba(255,122,61,0.82)" });
  });

  it("corrFooterWithoutAnyLinkSaysNoData", async () => {
    // nova has two cognitive results and matches on other days: no pair reaches three days
    const t = await seedCardTeam(deps);
    await connect(t.ids.nova, FACEIT_IDS.nova, "nova_f");
    const { markup } = await open(t, { player: t.ids.nova, tab: "corr" }, fakeFaceit({ [FACEIT_IDS.nova]: CORR_ITEMS }));
    expect(text(markup)).toContain("Главное: нет данных");
    expect(text(markup)).not.toContain("сильнее всего связаны");
    expect(matrixOf(markup).rows.flatMap((r) => r.cells.map((c) => c.text))).toEqual(Array(15).fill("мало данных"));
  });
});

// ---------------------------------------------------------------- Faceit problems

const MESSAGES = {
  noKey: "Faceit не подключён к серверу",
  noPlayer: "У игрока не подключён Faceit",
  notFound: "Игрок на Faceit не найден",
  unavailable: "Faceit временно недоступен — попробуйте позже",
};

describe("Faceit problems", () => {
  it("faceitFailuresDegradeTheCard", async () => {
    const t = await seedCardTeam(deps);
    await connect(t.ids.vega, FACEIT_IDS.vega);
    const cases: [string, FakeFaceit | null, string][] = [
      ["unavailable", fakeFaceit({ [FACEIT_IDS.vega]: 500 }), MESSAGES.unavailable],
      ["rate-limited", fakeFaceit({ [FACEIT_IDS.vega]: 429 }), MESSAGES.unavailable],
      ["not-found", fakeFaceit({ [FACEIT_IDS.vega]: 404 }), MESSAGES.notFound],
    ];
    for (const tab of ["game", "corr"]) {
      for (const [name, faceit, message] of cases) {
        await deps.sql`delete from faceit_cache`;
        const { markup } = await open(t, { player: t.ids.vega, tab }, faceit);
        expect(text(markup), `${tab} ${name}`).toContain(message);
        expect(markup, `${tab} ${name}`).toContain('role="status"');
        expect(text(markup), `${tab} ${name}`).not.toContain("Данные от");
        expect(text(markup), `${tab} ${name}`).not.toMatch(/Убийства|Таблица|мало данных|Главное/);
      }
    }

    // no key on the server
    vi.stubEnv("FACEIT_API_KEY", "");
    const noKey = await open(t, { player: t.ids.vega, tab: "game" });
    expect(text(noKey.markup)).toContain(MESSAGES.noKey);
    vi.stubEnv("FACEIT_API_KEY", KEY);

    // the player has not connected Faceit: nova has no `faceit` document, and a document without id and nickname counts too
    const noPlayer = await open(t, { player: t.ids.nova, tab: "corr" }, fakeFaceit({}));
    expect(text(noPlayer.markup)).toContain(MESSAGES.noPlayer);
    await putDoc(deps, t.ids.nova, "faceit", { nickname: null, playerId: null, snapshots: [] });
    const empty = await open(t, { player: t.ids.nova, tab: "game" }, fakeFaceit({}));
    expect(text(empty.markup)).toContain(MESSAGES.noPlayer);

    // every other tab of the same card still loads, whatever Faceit says
    for (const tab of ["profile", "tests", "wheel", "food", "sleep"]) {
      const other = await open(t, { player: t.ids.vega, tab }, fakeFaceit({ [FACEIT_IDS.vega]: 500 }));
      expect(text(other.markup), tab).not.toMatch(/подключён|не найден|недоступен|Данные от/);
      expect(other.markup, tab).not.toContain('role="status"');
    }
    const tests = await open(t, { player: t.ids.vega, tab: "tests" });
    expect(text(tests.markup)).toContain("Скорость реакции");
    expect(text(tests.markup)).toContain("Средний score 70");
  });

  it("staleDataIsShownWithItsDate", async () => {
    const t = await seedCardTeam(deps);
    await connect(t.ids.vega, FACEIT_IDS.vega);
    const mapped = GAME_ITEMS.map((raw) => mapMatch(raw)).filter((m) => m !== null);
    // fetched 2.5 hours before the test clock (26 Oct 12:00 UTC): older than the 15-minute cache, Faceit is down
    await deps.sql`
      insert into faceit_cache (user_id, matches, fetched_at)
      values (${t.ids.vega}, ${deps.sql.json(mapped as never)}, ${new Date("2026-10-26T09:30:00Z")})`;
    const { markup } = await open(t, { player: t.ids.vega, tab: "game" }, fakeFaceit({ [FACEIT_IDS.vega]: 500 }));
    const words = text(markup);
    expect(words).toContain(`${MESSAGES.unavailable}. Данные от 26 окт, 12:30`);
    expect(tileMap(markup)["Убийства"]).toBe("49");

    const corr = await open(t, { player: t.ids.vega, tab: "corr" }, fakeFaceit({ [FACEIT_IDS.vega]: 429 }));
    expect(text(corr.markup)).toContain("Данные от 26 окт, 12:30");
    expect(corr.markup).toContain("Таблица корреляций");
  });

  it("freshDataHasNoNotice", async () => {
    const t = await seedCardTeam(deps);
    await connect(t.ids.vega, FACEIT_IDS.vega);
    const { markup } = await open(t, { player: t.ids.vega, tab: "game" }, fakeFaceit({ [FACEIT_IDS.vega]: GAME_ITEMS }));
    expect(markup).not.toContain('role="status"');
    expect(text(markup)).not.toMatch(/Данные от|Матчи покрыты/);
  });

  it("periodBeyondCoverageSaysFromWhichDate", async () => {
    const t = await seedCardTeam(deps);
    await connect(t.ids.vega, FACEIT_IDS.vega);
    // 100 matches, one every 12 hours back from 26 Oct 10:00 UTC: the oldest is 7 Sep (01:00 Moscow)
    const hundred = Array.from({ length: 100 }, (_, i) => item(new Date(ms("2026-10-26T10:00:00Z") - i * 12 * 3_600_000).toISOString()));
    const faceit = fakeFaceit({ [FACEIT_IDS.vega]: hundred });

    for (const tab of ["game", "corr"]) {
      await deps.sql`delete from faceit_cache`;
      const wide = await open(t, { player: t.ids.vega, tab, period: "90" }, faceit);
      expect(text(wide.markup), tab).toContain("Матчи покрыты с 7 сен");
      // a period that starts after the oldest match is covered completely
      const short = await open(t, { player: t.ids.vega, tab, period: "30" }, faceit);
      expect(text(short.markup), tab).not.toContain("Матчи покрыты");
    }

    // fewer than 100 matches: Faceit returned them all, so there is nothing to apologise for
    await deps.sql`delete from faceit_cache`;
    const few = await open(t, { player: t.ids.vega, tab: "game", period: "90" }, fakeFaceit({ [FACEIT_IDS.vega]: hundred.slice(0, 40) }));
    expect(text(few.markup)).not.toContain("Матчи покрыты");
  });
});

// ---------------------------------------------------------------- who is asked, and when

describe("who and when Faceit is asked", () => {
  it("noFaceitCallOnOtherTabs", async () => {
    const t = await seedCardTeam(deps);
    await connect(t.ids.vega, FACEIT_IDS.vega);
    const spy = vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("the network must not be used"));
    for (const tab of ["profile", "tests", "wheel", "food", "sleep"]) {
      for (const src of ["all", "faceit"]) {
        const { markup } = await open(t, { player: t.ids.vega, tab, src });
        expect(markup, tab).toContain("player-card");
      }
    }
    expect(spy).not.toHaveBeenCalled();
    const [{ n }] = await deps.sql<{ n: number }[]>`select count(*)::int as n from faceit_cache`;
    expect(n).toBe(0);

    // the two Faceit tabs do ask (and a failing network is only a message)
    for (const tab of ["game", "corr"]) {
      const { markup } = await open(t, { player: t.ids.vega, tab });
      expect(text(markup), tab).toContain(MESSAGES.unavailable);
    }
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it("onlyTheRosterCheckedPlayerIsAsked", async () => {
    const t = await seedCardTeam(deps);
    await connect(t.ids.vega, FACEIT_IDS.vega);
    await connect(t.ids.nova, FACEIT_IDS.nova, "nova_f");
    await connect(t.ids.outsider, FACEIT_IDS.outsider, "zed_f");
    const faceit = fakeFaceit({ [FACEIT_IDS.vega]: GAME_ITEMS, [FACEIT_IDS.nova]: GAME_ITEMS, [FACEIT_IDS.outsider]: GAME_ITEMS });

    // another team's player, a solo player and garbage fall back to the first roster player
    for (const player of [t.ids.outsider, t.ids.solo, "not-an-id", `${t.ids.vega}' or 1=1 --`]) {
      await deps.sql`delete from faceit_cache`;
      faceit.calls.length = 0;
      const { data } = await open(t, { player, tab: "game" }, faceit);
      expect(faceit.calls.length, player).toBe(1);
      expect(faceit.calls[0], player).not.toContain(FACEIT_IDS.outsider);
      const expected = { [t.ids.vega]: FACEIT_IDS.vega, [t.ids.nova]: FACEIT_IDS.nova }[data.player.id];
      expect(expected, `${player}: the card shows a roster player with a Faceit id`).toBeDefined();
      expect(faceit.calls[0], player).toContain(`/players/${expected}/`);
    }

    // asking for nova asks for nova only
    await deps.sql`delete from faceit_cache`;
    faceit.calls.length = 0;
    await open(t, { player: t.ids.nova, tab: "corr" }, faceit);
    expect(faceit.calls).toEqual([`https://open.faceit.com/data/v4/players/${FACEIT_IDS.nova}/games/cs2/stats?limit=100`]);
  });

  it("anUnsafeSyncedPlayerIdIsNeverPutInAUrl", async () => {
    const t = await seedCardTeam(deps);
    await connect(t.ids.vega, "../../admin?x=1", "vega_f");
    const faceit = fakeFaceit({});
    const { markup } = await open(t, { player: t.ids.vega, tab: "game" }, faceit);
    expect(faceit.calls.every((url) => !url.includes("admin"))).toBe(true);
    expect(text(markup)).toContain(MESSAGES.notFound);
  });
});
