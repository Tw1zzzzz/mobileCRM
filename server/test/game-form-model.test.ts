import { afterEach, describe, expect, it, vi } from "vitest";
import { checkDraft } from "@/components/staff/game-stats/check";
import {
  CELLS, GAME_KIND_OPTIONS, GAME_MAP_OPTIONS, MAX_MAPS, MAX_NICKNAME, MAX_OPPONENT, MAX_ROWS, TEAM, draftFromGame, isBlank, newDraft,
  absentRows, normalizeCell, readCell, readScore, withAbsent, toGameInput, visibleRows, withCell, withNewMap, withPastedCells, withRoster, withStandIn, withWho,
  type Draft, type RosterPlayer,
} from "@/components/staff/game-stats/draft";
import { liveSummary, rowFigures } from "@/components/staff/game-stats/figures";
import { parsePaste, pasteCells } from "@/components/staff/game-stats/paste";
import { loadAttemptId, readDraft, storeAttemptId, writeDraft } from "@/components/staff/game-stats/storage";
import { GAME_KINDS, GAME_MAPS, MAX_GAME_MAPS, MAX_ROWS_PER_MAP, parseGameInput, type Game } from "@/lib/staff/games";

const A: RosterPlayer = { id: "a1111111-1111-4111-8111-111111111111", nickname: "vega" };
const B: RosterPlayer = { id: "b2222222-2222-4222-8222-222222222222", nickname: "nova" };
const GONE: RosterPlayer = { id: "c3333333-3333-4333-8333-333333333333", nickname: "left-the-team" };

const fresh = (roster: readonly RosterPlayer[] = [A, B]): Draft => newDraft(roster, "2026-11-04");

function played(draft: Draft, over: Partial<Draft["maps"][number]> = {}): Draft {
  return { ...draft, maps: [{ ...draft.maps[0], map: "mirage", our: "13", their: "7", pistol1: "won", pistol2: "lost", ...over }, ...draft.maps.slice(1)] };
}

describe("the form agrees with the server's limits", () => {
  it("usesTheServersKindsMapsAndCounts", () => {
    expect(GAME_KIND_OPTIONS.map((o) => o.value)).toEqual([...GAME_KINDS]);
    expect(GAME_MAP_OPTIONS.map((o) => o.value).sort()).toEqual([...GAME_MAPS].sort());
    expect([MAX_MAPS, MAX_ROWS]).toEqual([MAX_GAME_MAPS, MAX_ROWS_PER_MAP]);
  });

  it("everyBoundOfACellIsTheServers", () => {
    const rowOf = (over: object) => ({ kind: "pracc", playedOn: "2026-11-04", maps: [{ map: "mirage", ourScore: 1, theirScore: 1, pistol1: "won", pistol2: "won", rows: [{ playerId: A.id, kills: 1, deaths: 1, assists: 1, adr: 1, ...over }] }] });
    for (const spec of CELLS) {
      expect(readCell(spec.key, String(spec.max)).kind).toBe("ok");
      expect(readCell(spec.key, String(spec.max + 1)).kind).toBe("bad");
      expect(() => parseGameInput(rowOf({ [spec.key]: spec.max }))).not.toThrow();
      expect(() => parseGameInput(rowOf({ [spec.key]: spec.max + 1 }))).toThrow();
    }
    expect(MAX_OPPONENT).toBe(60);
    expect(MAX_NICKNAME).toBe(40);
  });

  it("roundsExtraDecimalsInsteadOfRefusingThem", () => {
    expect(readCell("adr", "76,25")).toEqual({ kind: "ok", value: 76.3 });
    expect(readCell("adr", "76.249")).toEqual({ kind: "ok", value: 76.2 });
    expect(readCell("kast", "99,96")).toEqual({ kind: "ok", value: 100 });
    expect(readCell("udr", "1.15")).toEqual({ kind: "ok", value: 1.2 });
    expect(readCell("kills", "24,5")).toEqual({ kind: "ok", value: 25 });
    // Rounding happens first, so a value that rounds past the limit is still refused.
    expect(readCell("kast", "100,06")).toEqual({ kind: "bad" });
    expect(readCell("adr", "300,04")).toEqual({ kind: "ok", value: 300 });
    expect(normalizeCell("adr", "76.25")).toBe("76,3");
    expect(normalizeCell("adr", "80")).toBe("80");
    expect(normalizeCell("adr", "abc")).toBe("abc");
    // What the form sends is the rounded number.
    let draft = withCell(withCell(played(fresh()), 0, A.id, "adr", "76,25"), 0, A.id, "kast", "55.55");
    draft = withCell(draft, 0, A.id, "udr", "12,34");
    const row = toGameInput(draft).maps[0].rows[0];
    expect(row).toMatchObject({ adr: 76.3, kast: 55.6, udr: 12.3 });
  });

  it("ignoresOneTrailingPercentSign", () => {
    expect(readCell("kast", "78%")).toEqual({ kind: "ok", value: 78 });
    expect(readCell("kast", "78,55 %")).toEqual({ kind: "ok", value: 78.6 });
    expect(readCell("kast", "%")).toEqual({ kind: "bad" });
    expect(readCell("kast", "78%%")).toEqual({ kind: "bad" });
    expect(normalizeCell("kast", "78,5%")).toBe("78,5");
    const pasted = withPastedCells(fresh(), 0, pasteCells(parsePaste("78%\t80%"), { row: 0, col: 4 }, { rows: 2, cols: CELLS.length }));
    expect(pasted.maps[0].rows[0].cells).toMatchObject({ kast: "78", fk: "80" });
  });

  it("readsCellsTheWayTheServerWantsThem", () => {
    expect(readCell("kills", " 24 ")).toEqual({ kind: "ok", value: 24 });
    expect(readCell("adr", "91,5")).toEqual({ kind: "ok", value: 91.5 });
    expect(readCell("adr", "91.5")).toEqual({ kind: "ok", value: 91.5 });
    expect(readCell("adr", "abc")).toEqual({ kind: "bad" });
    expect(readCell("adr", "-1")).toEqual({ kind: "bad" });
    expect(readCell("fk", "")).toEqual({ kind: "empty" });
    expect(readScore("30")).toBe(30);
    expect(readScore("31")).toBeNull();
    expect(readScore("")).toBeNull();
    expect(readScore("1.5")).toBeNull();
  });
});

describe("the draft", () => {
  it("startsWithARowPerRosterPlayerAndNothingTyped", () => {
    const draft = fresh();
    expect(visibleRows(draft, draft.maps[0]).map((r) => r.nickname)).toEqual(["vega", "nova"]);
    expect(isBlank(draft)).toBe(true);
    expect(isBlank(withCell(draft, 0, A.id, "kills", "1"))).toBe(false);
    expect(isBlank(withStandIn(draft, 0))).toBe(false);
  });

  it("changesNeverTouchTheOldDraft", () => {
    const draft = fresh();
    const next = withCell(draft, 0, A.id, "kills", "7");
    expect(draft.maps[0].rows[0].cells.kills).toBe("");
    expect(next.maps[0].rows[0].cells.kills).toBe("7");
    expect(next.maps[0].rows[1]).toBe(draft.maps[0].rows[1]);
  });

  it("anElevenColumnBlockLandsInTheBoardsColumns", () => {
    expect(CELLS.map((c) => c.label)).toEqual(["K", "D", "A", "ADR", "KAST %", "FK", "FD", "UDR", "Мульти", "Клатчи", "Ослепл."]);
    const text = "24\t15\t5\t91,25\t78,5\t5\t2\t14,7\t3\t1\t2,2\n18\t17\t7\t76\t\t\t\t\t\t\t";
    const cells = pasteCells(parsePaste(text), { row: 0, col: 0 }, { rows: 2, cols: CELLS.length });
    const rows = withPastedCells(fresh(), 0, cells).maps[0].rows;
    expect(rows[0].cells).toEqual({
      kills: "24", deaths: "15", assists: "5", adr: "91,3", kast: "78,5", fk: "5", fd: "2", udr: "14,7",
      multikills: "3", clutches: "1", flashTime: "2,2",
    });
    expect(rows[1].cells).toMatchObject({ kills: "18", adr: "76", kast: "", fk: "", udr: "", flashTime: "" });
    // The same block pasted from the FK cell shifts right: FK, FD, UDR, Мульти, Клатчи, Ослепл. get the first six.
    const shifted = withPastedCells(fresh(), 0, pasteCells(parsePaste("1\t2\t3\t4\t5\t6\t7"), { row: 0, col: 5 }, { rows: 2, cols: CELLS.length }));
    expect(shifted.maps[0].rows[0].cells).toMatchObject({ fk: "1", fd: "2", udr: "3", multikills: "4", clutches: "5", flashTime: "6", adr: "" });
  });

  it("aPasteFillsFromTheCellRightAndDown", () => {
    const grid = parsePaste("24\t15\t5\t91,5\n18\t17\t7\t76");
    const cells = pasteCells(grid, { row: 0, col: 0 }, { rows: 2, cols: CELLS.length });
    const draft = withPastedCells(fresh(), 0, cells);
    expect(draft.maps[0].rows.map((r) => [r.cells.kills, r.cells.deaths, r.cells.assists, r.cells.adr])).toEqual([
      ["24", "15", "5", "91,5"], ["18", "17", "7", "76"],
    ]);
  });

  it("aPasteCountsRowsAmongTheShownOnes", () => {
    const single = withWho(fresh(), B.id);
    const cells = pasteCells(parsePaste("9\t8\n7\t6"), { row: 0, col: 1 }, { rows: 1, cols: CELLS.length });
    const draft = withPastedCells(single, 0, cells);
    // Only nova is shown: the first pasted line lands on her, the second has no row.
    expect(draft.maps[0].rows.find((r) => r.playerId === B.id)?.cells).toMatchObject({ kills: "", deaths: "9", assists: "8" });
    expect(draft.maps[0].rows.find((r) => r.playerId === A.id)?.cells.deaths).toBe("");
  });

  it("aNewMapCarriesThePlayersAndStandInsButNotTheNumbers", () => {
    let draft = withStandIn(fresh(), 0);
    const standIn = draft.maps[0].rows[2];
    draft = { ...draft, maps: [{ ...draft.maps[0], rows: draft.maps[0].rows.map((r) => (r === standIn ? { ...r, nickname: "ringer" } : r)) }] };
    draft = withCell(draft, 0, A.id, "kills", "20");
    const next = withNewMap(draft);
    expect(next.maps).toHaveLength(2);
    expect(next.active).toBe(1);
    expect(next.maps[1].rows.map((r) => r.nickname)).toEqual(["vega", "nova", "ringer"]);
    expect(next.maps[1].rows.every((r) => Object.values(r.cells).every((c) => c === ""))).toBe(true);
    expect(Array.from({ length: 8 }).reduce<Draft>((d) => withNewMap(d), draft).maps).toHaveLength(MAX_MAPS);
  });

  it("aSinglePlayerGameShowsAndSendsOnlyThatPlayer", () => {
    let draft = withWho(played(fresh()), A.id);
    for (const [key, value] of [["kills", "20"], ["deaths", "10"], ["assists", "4"], ["adr", "80"]] as const) draft = withCell(draft, 0, A.id, key, value);
    expect(checkDraft(draft).issues).toEqual([]);
    const input = toGameInput(draft);
    expect(input.maps[0].rows).toHaveLength(1);
    expect(input.maps[0].rows[0]).toMatchObject({ playerId: A.id, kills: 20, fk: null });
    expect(liveSummary(draft).kdLabel).toBe("K/D");
    expect(liveSummary(withWho(draft, TEAM)).kdLabel).toBe("K/D команды");
  });

  it("whatTheFormSendsPassesTheServersValidation", () => {
    let draft = played(withStandIn(fresh(), 0));
    draft = { ...draft, opponent: "  Natus Vincere ", maps: [{ ...draft.maps[0], rows: draft.maps[0].rows.map((r) => (r.playerId === null ? { ...r, nickname: " ringer " } : r)) }] };
    for (const row of draft.maps[0].rows) {
      for (const [key, value] of [["kills", "20"], ["deaths", "0"], ["assists", "4"], ["adr", "80,5"], ["fk", "3"], ["multikills", "1,5"]] as const) {
        draft = withCell(draft, 0, row.key, key, value);
      }
    }
    expect(checkDraft(draft).issues).toEqual([]);
    const input = toGameInput(draft, "d4444444-4444-4444-8444-444444444444");
    expect(input).toMatchObject({ id: "d4444444-4444-4444-8444-444444444444", kind: "pracc", opponent: "Natus Vincere", playedOn: "2026-11-04" });
    expect(input.maps[0].rows[2]).toMatchObject({ nickname: "ringer", adr: 80.5, multikills: 1.5, fd: null });
    expect(() => parseGameInput(input)).not.toThrow();
  });
});

describe("«Не играл»", () => {
  it("leavesARosterPlayerOutOfOneMapAndBringsThemBack", () => {
    let draft = withNewMap(fresh());
    draft = withAbsent(draft, 0, B.id, true);
    expect(visibleRows(draft, draft.maps[0]).map((r) => r.nickname)).toEqual(["vega"]);
    expect(absentRows(draft, draft.maps[0]).map((r) => r.nickname)).toEqual(["nova"]);
    expect(visibleRows(draft, draft.maps[1]).map((r) => r.nickname)).toEqual(["vega", "nova"]);
    expect(isBlank(withAbsent(fresh(), 0, B.id, true))).toBe(false);
    draft = withAbsent(draft, 0, B.id, false);
    expect(visibleRows(draft, draft.maps[0])).toHaveLength(2);
    expect(absentRows(draft, draft.maps[0])).toEqual([]);
  });

  it("theChecklistAndTheSentGameFollowIt", () => {
    let draft = played(fresh());
    expect(checkDraft(draft).issues).toContain("Карта 1: не заполнено 8 обязательных ячеек");
    draft = withAbsent(draft, 0, B.id, true);
    expect(checkDraft(draft).issues).toContain("Карта 1: не заполнено 4 обязательные ячейки");
    for (const [key, value] of [["kills", "20"], ["deaths", "10"], ["assists", "4"], ["adr", "80"]] as const) draft = withCell(draft, 0, A.id, key, value);
    expect(checkDraft(draft).issues).toEqual([]);
    expect(toGameInput(draft).maps[0].rows.map((r) => r.playerId)).toEqual([A.id]);
    // A pasted block lands on the players who are shown.
    const second = withPastedCells(draft, 0, pasteCells(parsePaste("1\t2\n3\t4"), { row: 0, col: 0 }, { rows: 1, cols: CELLS.length }));
    expect(second.maps[0].rows.find((r) => r.playerId === B.id)?.cells.kills).toBe("");
  });

  it("atLeastOneLineStaysRequired", () => {
    let draft = withAbsent(withAbsent(played(fresh()), 0, A.id, true), 0, B.id, true);
    expect(checkDraft(draft).issues).toContain("Карта 1: добавьте хотя бы одного игрока");
    // A stand-in is a line too.
    draft = withStandIn(draft, 0);
    expect(checkDraft(draft).issues).not.toContain("Карта 1: добавьте хотя бы одного игрока");
  });

  it("aSinglePlayerGameIgnoresIt", () => {
    const draft = withWho(withAbsent(fresh(), 0, A.id, true), A.id);
    expect(visibleRows(draft, draft.maps[0]).map((r) => r.nickname)).toEqual(["vega"]);
    expect(absentRows(draft, draft.maps[0])).toEqual([]);
  });

  it("isKeptWithTheDraft", () => {
    const draft = withAbsent(fresh(), 0, B.id, true);
    expect(readDraft(writeDraft(draft))?.maps[0].rows.map((r) => r.absent)).toEqual([false, true]);
    expect(withRoster(draft, [A, B], { keep: new Set(), fill: true }).maps[0].rows.map((r) => r.absent)).toEqual([false, true]);
    expect(withNewMap(draft).maps[1].rows.map((r) => r.absent)).toEqual([false, true]);
  });
});

describe("the id of a game being sent", () => {
  const ID = "d4444444-4444-4444-8444-444444444444";
  const store = new Map<string, string>();
  const stub = () =>
    vi.stubGlobal("window", {
      localStorage: {
        getItem: (k: string) => store.get(k) ?? null,
        setItem: (k: string, v: string) => void store.set(k, v),
        removeItem: (k: string) => void store.delete(k),
      },
    });
  afterEach(() => {
    vi.unstubAllGlobals();
    store.clear();
  });

  it("survivesAReloadUntilItIsCleared", () => {
    stub();
    expect(loadAttemptId("t1")).toBeNull();
    storeAttemptId("t1", ID);
    expect(loadAttemptId("t1")).toBe(ID);
    expect(loadAttemptId("t2")).toBeNull();
    storeAttemptId("t1", null);
    expect(loadAttemptId("t1")).toBeNull();
  });

  it("onlyAUuidIsTrustedFromStorage", () => {
    stub();
    store.set("pulse.staff.game-draft.t1.attempt", "not-an-id");
    expect(loadAttemptId("t1")).toBeNull();
  });

  it("worksWithoutStorage", () => {
    vi.stubGlobal("window", { get localStorage(): never { throw new Error("blocked"); } });
    expect(loadAttemptId("t1")).toBeNull();
    expect(() => storeAttemptId("t1", ID)).not.toThrow();
  });
});

describe("the checklist", () => {
  it("countsMissingAndWrongCells", () => {
    let draft = played(fresh(), { our: "31" });
    draft = withCell(draft, 0, A.id, "kills", "x");
    draft = withCell(draft, 0, A.id, "adr", "301");
    const issues = checkDraft(draft).issues;
    expect(issues).toContain("Карта 1: счёт — целые числа от 0 до 30");
    expect(issues).toContain("Карта 1: не заполнено 6 обязательных ячеек");
    expect(issues).toContain("Карта 1: 2 ячейки с недопустимым значением");
  });

  it("asksForTheDateTheStandInNicknameAndRowLimits", () => {
    let draft = withStandIn({ ...fresh(), playedOn: "" }, 0);
    expect(checkDraft(draft).issues).toContain("Укажите дату игры");
    expect(checkDraft(draft).issues).toContain("Карта 1: укажите ник запасного");
    for (let i = 0; i < MAX_ROWS; i += 1) draft = withStandIn(draft, 0);
    expect(checkDraft(draft).issues).toContain(`Карта 1: не больше ${MAX_ROWS} игроков на карте`);
  });

  it("numbersEachMapOfTheGame", () => {
    const draft = withNewMap(played(fresh()));
    const issues = checkDraft(draft).issues;
    expect(issues.some((i) => i.startsWith("Карта 1: не заполнено"))).toBe(true);
    expect(issues).toContain("Карта 2: выберите карту");
    expect(checkDraft(draft).maps.map((m) => m.complete)).toEqual([false, false]);
  });
});

describe("the live numbers", () => {
  it("figuresOfARow", () => {
    let draft = played(fresh(), { our: "13", their: "7" });
    for (const [key, value] of [["kills", "20"], ["deaths", "0"], ["fk", "5"], ["fd", "2"]] as const) draft = withCell(draft, 0, A.id, key, value);
    expect(rowFigures(draft.maps[0], draft.maps[0].rows[0])).toEqual({ kd: "20,00", kpr: "1,00", dpr: "0,00", open: "+3" });
    expect(rowFigures(draft.maps[0], draft.maps[0].rows[1])).toEqual({ kd: "—", kpr: "—", dpr: "—", open: "—" });
    // Without a score there are no rounds: no KPR, DPR.
    expect(rowFigures({ ...draft.maps[0], our: "" }, draft.maps[0].rows[0])).toMatchObject({ kd: "20,00", kpr: "—", dpr: "—" });
  });

  it("summaryOfMapsRoundsAndPistols", () => {
    let draft = played(fresh(), { our: "13", their: "9", pistol1: "won", pistol2: "won" });
    draft = withNewMap(draft);
    draft = { ...draft, maps: [draft.maps[0], { ...draft.maps[1], map: "nuke", our: "10", their: "13", pistol1: "lost", pistol2: "won" }] };
    draft = withNewMap(draft);
    draft = { ...draft, maps: [...draft.maps.slice(0, 2), { ...draft.maps[2], our: "12", their: "12" }] };
    expect(liveSummary(draft)).toMatchObject({
      maps: "1 : 1 : 1", mapsSub: "по картам · 3 карты (П : П : Н)", winRate: "33%", roundWinRate: "51%", rounds: "35–34",
      pistolWinRate: "75%", avgRoundsWon: "11,7", avgRoundsLost: "11,3",
    });
  });

  it("nothingIsInventedBeforeThereIsAScore", () => {
    expect(liveSummary(fresh())).toEqual({
      maps: "—", mapsSub: "введите счёт карты", winRate: "—", roundWinRate: "—", rounds: "—", pistolWinRate: "—",
      avgRoundsWon: "—", avgRoundsLost: "—", kdLabel: "K/D команды", kd: "—",
    });
  });
});

describe("the roster", () => {
  it("aNewGameDropsAPlayerWhoLeftAndAddsOneWhoJoined", () => {
    const saved = withCell(fresh([A, GONE]), 0, GONE.id, "kills", "9");
    const draft = withRoster(saved, [A, B], { keep: new Set(), fill: true });
    expect(draft.maps[0].rows.map((r) => r.nickname)).toEqual(["vega", "nova"]);
    expect(withRoster({ ...saved, who: GONE.id }, [A, B], { keep: new Set(), fill: true }).who).toBe(TEAM);
  });

  it("anOldGameKeepsItsPlayersEvenAfterTheyLeft", () => {
    const game = {
      id: "d4444444-4444-4444-8444-444444444444", kind: "tournament", playedOn: "2026-10-01", opponent: null, createdBy: null,
      createdAt: new Date(), updatedAt: new Date(),
      maps: [{
        id: "m1", position: 0, map: "vertigo", ourScore: 13, theirScore: 10, pistol1: "won", pistol2: "won",
        rows: [
          { id: "r1", playerId: A.id, nickname: "vega-then", kills: 20, deaths: 10, assists: 3, adr: 80.5, kast: 71.5, udr: null, fk: 2, fd: null, multikills: null, clutches: 1, flashTime: 2.5 },
          { id: "r2", playerId: GONE.id, nickname: GONE.nickname, kills: 10, deaths: 14, assists: 2, adr: 55, kast: null, udr: null, fk: null, fd: null, multikills: null, clutches: null, flashTime: null },
          { id: "r3", playerId: null, nickname: "ringer", kills: 5, deaths: 5, assists: 5, adr: 50, kast: null, udr: null, fk: null, fd: null, multikills: null, clutches: null, flashTime: null },
        ],
      }],
    } as Game;
    const draft = draftFromGame(game, [A, B]);
    // vega shows under her current nickname; nova (not on the game) gets no line; the player who left stays.
    expect(draft.maps[0].rows.map((r) => r.nickname)).toEqual(["vega", "left-the-team", "ringer"]);
    expect(draft).toMatchObject({ id: game.id, kind: "tournament", opponent: "", who: TEAM });
    expect(draft.maps[0].rows[0].cells).toMatchObject({ kills: "20", kast: "71,5", fk: "2", fd: "", udr: "", flashTime: "2,5" });
    expect(checkDraft(draft).issues).toEqual([]);
    const back = toGameInput(draft);
    expect(back).toMatchObject({ id: game.id, opponent: null });
    expect(back.maps[0].rows.map((r) => r.playerId ?? r.nickname)).toEqual([A.id, GONE.id, "ringer"]);
    expect(() => parseGameInput(back)).not.toThrow();
  });

  it("aGameOfOnePlayerOpensAsThatPlayer", () => {
    const row = (id: string) => ({ id, playerId: B.id, nickname: "nova", kills: 1, deaths: 1, assists: 1, adr: 1, kast: null, udr: null, fk: null, fd: null, multikills: null, clutches: null, flashTime: null });
    const map = (id: string) => ({ id, position: 0, map: "mirage", ourScore: 1, theirScore: 0, pistol1: "won", pistol2: "won", rows: [row(`${id}r`)] });
    const game = { id: "e5555555-5555-4555-8555-555555555555", kind: "pracc", playedOn: "2026-10-01", opponent: "x", maps: [map("m1"), map("m2")] } as unknown as Game;
    expect(draftFromGame(game, [A, B]).who).toBe(B.id);
    // The same game once that player has left reads as a team game.
    expect(draftFromGame(game, [A]).who).toBe(TEAM);
  });
});

describe("the stored draft", () => {
  it("comesBackAsItWasSaved", () => {
    const draft = withCell(played(withStandIn(fresh(), 0)), 0, A.id, "kills", "21");
    expect(readDraft(writeDraft(draft))).toEqual(draft);
  });

  it("refusesWhatIsNotADraft", () => {
    expect(readDraft(null)).toBeNull();
    expect(readDraft("not json")).toBeNull();
    expect(readDraft("[]")).toBeNull();
    expect(readDraft(JSON.stringify({ v: 2, draft: fresh() }))).toBeNull();
    expect(readDraft(JSON.stringify({ v: 1, draft: { ...fresh(), maps: [] } }))).toBeNull();
    // A draft of an edited game is never restored as a new one.
    expect(readDraft(writeDraft({ ...fresh(), id: "d4444444-4444-4444-8444-444444444444" }))).toBeNull();
  });

  it("rebuildsEveryFieldSoJunkCannotGetIn", () => {
    const stored = JSON.stringify({
      v: 1,
      draft: {
        id: null, kind: "pracc", playedOn: "2026-11-04", opponent: "x".repeat(200), who: "team", active: 99,
        maps: [{ map: "cache", our: "123456", their: 7, pistol1: "draw", pistol2: "won", extra: true,
          rows: [{ key: "k", playerId: null, nickname: "n", cells: { kills: "1".repeat(50), bogus: "z" } }, { nope: 1 }] }],
      },
    });
    const draft = readDraft(stored);
    expect(draft).not.toBeNull();
    expect(draft?.opponent).toHaveLength(MAX_OPPONENT);
    expect(draft?.active).toBe(0);
    expect(draft?.maps[0]).toMatchObject({ map: "", our: "12", their: "", pistol1: "", pistol2: "won" });
    expect(draft?.maps[0].rows).toHaveLength(1);
    expect(draft?.maps[0].rows[0].cells.kills).toHaveLength(12);
    expect(draft?.maps[0].rows[0].cells).not.toHaveProperty("bogus");
  });
});
