import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { removeMember } from "../lib/team/members";
import {
  MAX_PLAYERS,
  isNutritionSection,
  loadNutrition,
  nutritionHref,
  parseNutritionParams,
  type NutritionData,
  type NutritionParams,
} from "../lib/staff/nutrition";
import { resetDb, testDeps } from "./helpers";
import { putDoc, seedCardTeam, type CardTeam } from "./staff-card-fixture";

const deps = testDeps();
const TZ = "Europe/Moscow";

beforeEach(async () => {
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

// The fixed test clock is Monday 2026-10-26, 15:00 in Moscow.
const PASTA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1";
const OATS = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2";
const STEAK = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3";
const YOGURT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa4";
const OLD = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa5";
const NOVA_MEAL = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1";
const FOREIGN = "cccccccc-cccc-4ccc-8ccc-ccccccccccc1";
const UNKNOWN = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";

const DEFAULTS: NutritionParams = { who: "all", days: 3, photos: false, meal: null };
const plain = (s: string): string => s.replace(/ /g, " ");

async function seed(): Promise<CardTeam> {
  const t = await seedCardTeam(deps);
  await putDoc(deps, t.ids.vega, "nutrition", {
    meals: [
      { id: OATS, date: "2026-10-26T06:00:00Z", name: "Овсянка", kcal: 340, protein: 11, fat: 8, carbs: 56, source: "photo", photoRef: true },
      { id: PASTA, date: "2026-10-26T10:30:00Z", name: "Паста", kcal: 687, protein: 31, fat: 22, carbs: 88, source: "manual" },
      { id: STEAK, date: "2026-10-25T16:00:00Z", name: "Стейк", kcal: 626, protein: 48, fat: 38, carbs: 14, source: "quick" },
      // 00:30 on 25 Oct in Moscow, although still 24 Oct in UTC
      { id: YOGURT, date: "2026-10-24T21:30:00Z", name: "Йогурт", kcal: 260, protein: 12, fat: 8, carbs: 34, source: "manual", photoRef: false },
      // outside the default three days
      { id: OLD, date: "2026-10-22T09:00:00Z", name: "Борщ", kcal: 300, protein: 10, fat: 9, carbs: 30, source: "manual" },
    ],
    water: { "2026-10-26": 1800 },
  });
  await putDoc(deps, t.ids.nova, "nutrition", {
    meals: [{ id: NOVA_MEAL, date: "2026-10-26T08:00:00Z", name: "Курица с рисом", kcal: 528, protein: 42, fat: 11, carbs: 64, source: "photo", photoRef: true }],
    goals: { kcal: 3000, protein: 150, fat: 90, carbs: 350, waterMilliliters: 3000 },
    water: { "2026-10-26": 1250 },
  });
  await putDoc(deps, t.ids.outsider, "nutrition", {
    meals: [{ id: FOREIGN, date: "2026-10-26T08:00:00Z", name: "Чужое блюдо", kcal: 999, source: "photo", photoRef: true }],
  });
  return t;
}

async function load(t: CardTeam, query: Record<string, string> = {}): Promise<NutritionData> {
  return loadNutrition(deps, t.ctx, { section: "nutrition", ...query }, TZ);
}

const names = (d: NutritionData) => d.groups.map((g) => g.meals.map((m) => m.name));

describe("parseNutritionParams", () => {
  const ids = ["p1", "p2"];

  it("defaultsToAllPlayersThreeDaysNoPhotoFilter", () => {
    expect(parseNutritionParams({}, ids)).toEqual(DEFAULTS);
    expect(isNutritionSection({ section: "nutrition" })).toBe(true);
    expect(isNutritionSection({ section: ["nutrition", "x"] })).toBe(true);
    expect(isNutritionSection({ section: "x" })).toBe(false);
    expect(isNutritionSection({})).toBe(false);
  });

  it("takesWhatTheUrlSays", () => {
    expect(parseNutritionParams({ who: "p2", days: "7", photos: "1", meal: PASTA }, ids)).toEqual({
      who: "p2",
      days: 7,
      photos: true,
      meal: PASTA,
    });
    expect(parseNutritionParams({ days: "1" }, ids).days).toBe(1);
    expect(parseNutritionParams({ who: "all" }, ids).who).toBe("all");
  });

  it("invalidValuesFallBackWithoutAnError", () => {
    expect(
      parseNutritionParams({ who: "someone-else", days: "30", photos: "yes", meal: "not-a-guid" }, ids),
    ).toEqual(DEFAULTS);
    expect(parseNutritionParams({ who: "", days: "", photos: "", meal: "" }, ids)).toEqual(DEFAULTS);
    expect(parseNutritionParams({ days: "0", who: "P1" }, ids)).toEqual(DEFAULTS);
    expect(parseNutritionParams({ who: ["p2", "p1"], days: ["7", "1"] }, ids)).toMatchObject({ who: "p2", days: 7 });
  });
});

describe("nutritionHref", () => {
  it("leavesDefaultsOutButAlwaysNamesTheSection", () => {
    expect(nutritionHref(DEFAULTS)).toBe("/staff/players?section=nutrition");
    expect(nutritionHref({ who: "p1", days: 7, photos: true, meal: PASTA })).toBe(
      `/staff/players?section=nutrition&who=p1&days=7&photos=1&meal=${PASTA}`,
    );
  });

  it("aPatchKeepsTheRestAndNullClosesTheDialog", () => {
    const current: NutritionParams = { who: "p1", days: 7, photos: true, meal: PASTA };
    expect(nutritionHref(current, { who: "p2", meal: null })).toBe("/staff/players?section=nutrition&who=p2&days=7&photos=1");
    expect(nutritionHref(current, { photos: false, meal: null })).toBe("/staff/players?section=nutrition&who=p1&days=7");
    expect(nutritionHref(current, { days: 3 })).toBe(`/staff/players?section=nutrition&who=p1&photos=1&meal=${PASTA}`);
    expect(nutritionHref(DEFAULTS, { meal: OATS })).toBe(`/staff/players?section=nutrition&meal=${OATS}`);
  });
});

describe("feed", () => {
  it("feedGroupsByStaffDayNewestFirst", async () => {
    const d = await load(await seed());
    expect(d.groups.map((g) => g.label)).toEqual([
      "Сегодня, 26 октября, понедельник",
      "Вчера, 25 октября, воскресенье",
    ]);
    // Within a day: newest first. The yogurt (21:30 UTC = 00:30 on the 25th in Moscow) is the oldest of the 25th.
    expect(names(d)).toEqual([["Паста", "Курица с рисом", "Овсянка"], ["Стейк", "Йогурт"]]);
    expect(d.groups[0].meals.map((m) => m.time)).toEqual(["13:30", "11:00", "09:00"]);
    expect(d.groups[1].meals.map((m) => m.time)).toEqual(["19:00", "00:30"]);
  });

  it("groupLabelsAndCounts", async () => {
    const d = await load(await seed(), { days: "7" });
    expect(d.groups.map((g) => g.label)).toEqual([
      "Сегодня, 26 октября, понедельник",
      "Вчера, 25 октября, воскресенье",
      "22 октября, четверг",
    ]);
    expect(d.groups.map((g) => g.sub)).toEqual(["3 записи", "2 записи", "1 запись"]);
    expect(d.countText).toBe("6 записей · 2 с фото");
  });

  it("aMealAt0030MoscowBelongsToTheStaffDayNotTheUtcDay", async () => {
    const d = await load(await seed(), { who: "", days: "3" });
    const yesterday = d.groups[1].meals.map((m) => m.name);
    expect(yesterday).toContain("Йогурт");
  });

  it("periodTodayShowsOnlyToday", async () => {
    const d = await load(await seed(), { days: "1" });
    expect(d.groups.map((g) => g.label)).toEqual(["Сегодня, 26 октября, понедельник"]);
    expect(d.countText).toBe("3 записи · 2 с фото");
  });

  it("cardTextIsKcalAndTimeAndThumbsNeedPhotoRef", async () => {
    const t = await seed();
    const d = await load(t);
    const today = d.groups[0].meals;
    expect(today.map((m) => [m.nickname, m.kcal, m.time])).toEqual([
      ["vega", "687", "13:30"],
      ["nova", "528", "11:00"],
      ["vega", "340", "09:00"],
    ]);
    const byName = new Map(today.map((m) => [m.name, m]));
    expect(byName.get("Овсянка")?.photo).toBe(`/staff/photos/${t.ids.vega}/${OATS}`);
    expect(byName.get("Курица с рисом")?.photo).toBe(`/staff/photos/${t.ids.nova}/${NOVA_MEAL}`);
    expect(byName.get("Паста")?.photo).toBeNull();
    expect(d.groups[1].meals.find((m) => m.name === "Йогурт")?.photo).toBeNull(); // photoRef: false
    expect(byName.get("Паста")?.href).toBe(`/staff/players?section=nutrition&meal=${PASTA}`);
  });

  it("playerFilterNarrowsTheFeedAndTheCountLine", async () => {
    const t = await seed();
    const d = await load(t, { who: t.ids.nova });
    expect(names(d)).toEqual([["Курица с рисом"]]);
    expect(d.countText).toBe("1 запись · 1 с фото · nova");
    expect(d.params.who).toBe(t.ids.nova);
  });

  it("onlyWithPhotoFilter", async () => {
    const d = await load(await seed(), { photos: "1" });
    expect(names(d)).toEqual([["Курица с рисом", "Овсянка"]]);
    expect(d.countText).toBe("2 записи · 2 с фото");
    expect(d.params.photos).toBe(true);
  });

  it("noDemoMealsInvented", async () => {
    const t = await seedCardTeam(deps); // players exist, nobody logged a meal
    const d = await load(t);
    expect(d.groups).toEqual([]);
    expect(d.countText).toBe("0 записей · 0 с фото");
    expect(d.players.map((p) => p.nickname)).toEqual(["vega", "nova", "ghost"]);
    expect(d.summary.map((s) => [s.meals, s.kcal, s.status.label])).toEqual([
      ["0 приёмов пищи", "0", "Нет записей"],
      ["0 приёмов пищи", "0", "Нет записей"],
      ["0 приёмов пищи", "0", "Нет записей"],
    ]);
  });

  it("onlyThisTeamsPlayersAreRead", async () => {
    const d = await load(await seed(), { days: "7" });
    const everything = JSON.stringify(d);
    expect(everything).not.toContain("Чужое блюдо");
    expect(everything).not.toContain(FOREIGN);
    expect(everything).not.toContain("zed-outsider");
    expect(everything).not.toContain("solo-loner");
  });

  it("aRemovedPlayerLeavesTheFeed", async () => {
    const t = await seed();
    await removeMember(deps, t.ids.team, t.ids.staff, t.ids.nova);
    const d = await load(t);
    expect(JSON.stringify(d)).not.toContain("Курица");
    expect(d.players.map((p) => p.nickname)).toEqual(["vega", "ghost"]);
  });

  it("junkInTheDocumentIsSkippedNotInvented", async () => {
    const t = await seedCardTeam(deps);
    await putDoc(deps, t.ids.ghost, "nutrition", {
      meals: [
        { id: "x1", date: "yesterday", name: "bad date", kcal: 1 },
        { date: "2026-10-26T06:00:00Z", name: "no id", kcal: 1 },
        { id: "x3", date: "2026-10-26T06:00:00Z", name: "  ", kcal: -5, protein: "lots", source: "robot" },
        { id: "x4", date: "2026-10-27T06:00:00Z", name: "future", kcal: 100 },
        "not a record",
      ],
      goals: { kcal: "many", waterMilliliters: 0 },
      water: [1, 2],
    });
    const d = await load(t);
    expect(names(d)).toEqual([["—"]]);
    expect(d.groups[0].meals[0].kcal).toBe("—");
    const row = d.summary.find((s) => s.nickname === "ghost");
    expect(plain(row?.goal ?? "")).toBe("2 400");
    expect(row?.water).toBe("Вода нет данных");
  });
});

describe("summary", () => {
  it("summaryUsesPlayerGoals", async () => {
    const t = await seed();
    const d = await load(t);
    const rows = new Map(d.summary.map((s) => [s.nickname, s]));
    const vega = rows.get("vega")!;
    expect([plain(vega.kcal), plain(vega.goal)]).toEqual(["1 027", "2 400"]);
    expect(vega.macros).toEqual(["Б 42 г", "Ж 30 г", "У 144 г"]);
    expect(vega.water).toBe("Вода 1,8 / 2,5 л");
    expect(vega.fill).toBe(43); // 1027 / 2400
    const nova = rows.get("nova")!;
    expect([nova.kcal, plain(nova.goal)]).toEqual(["528", "3 000"]);
    expect(nova.water).toBe("Вода 1,3 / 3,0 л");
    expect(nova.fill).toBe(18); // 528 / 3000
  });

  it("summaryStatusByMealCount", async () => {
    const d = await load(await seed());
    const rows = new Map(d.summary.map((s) => [s.nickname, s]));
    expect([rows.get("vega")!.meals, rows.get("vega")!.status]).toEqual(["2 приёма пищи", { label: "В норме", tone: "ok" }]);
    expect([rows.get("nova")!.meals, rows.get("nova")!.status]).toEqual(["1 приём пищи", { label: "Мало записей", tone: "mid" }]);
    expect([rows.get("ghost")!.meals, rows.get("ghost")!.status]).toEqual(["0 приёмов пищи", { label: "Нет записей", tone: "bad" }]);
    expect(rows.get("vega")!.warm).toBe(false);
    expect(rows.get("nova")!.warm).toBe(true);
    expect(rows.get("ghost")!.warm).toBe(true);
  });

  it("summaryIsTodayWhateverThePeriodAndFiltersSay", async () => {
    const t = await seed();
    const wide = await load(t, { days: "7", photos: "1", who: t.ids.nova });
    const plainDay = await load(t);
    expect(wide.summary.map((s) => [s.nickname, s.kcal, s.meals])).toEqual(
      plainDay.summary.map((s) => [s.nickname, s.kcal, s.meals]),
    );
  });

  it("summaryRowsTogglePlayerFilter", async () => {
    const t = await seed();
    const d = await load(t, { days: "7", who: t.ids.nova });
    const rows = new Map(d.summary.map((s) => [s.nickname, s]));
    expect(rows.get("nova")?.selected).toBe(true);
    expect(rows.get("nova")?.href).toBe("/staff/players?section=nutrition&days=7");
    expect(rows.get("vega")?.selected).toBe(false);
    expect(rows.get("vega")?.href).toBe(`/staff/players?section=nutrition&who=${t.ids.vega}&days=7`);
  });

  it("barIsCappedAtTheGoal", async () => {
    const t = await seedCardTeam(deps);
    await putDoc(deps, t.ids.vega, "nutrition", {
      meals: [{ id: PASTA, date: "2026-10-26T10:30:00Z", name: "Банкет", kcal: 5000 }],
    });
    const d = await load(t);
    expect(d.summary.find((s) => s.nickname === "vega")?.fill).toBe(100);
  });

  it("waterKeyIsUnpaddedPhoneDay", async () => {
    const t = await seedCardTeam(deps);
    await putDoc(deps, t.ids.vega, "nutrition", {
      meals: [],
      // Only the unpadded key is the phone's key; the padded one must be ignored.
      water: { "2026-11-05": 9999, "2026-11-5": 2100, "2026-10-26": 500 },
    });
    deps.advance(10 * 24 * 3600 * 1000); // 2026-11-05, 15:00 in Moscow
    const d = await load(t);
    expect(d.summary.find((s) => s.nickname === "vega")?.water).toBe("Вода 2,1 / 2,5 л");
    expect(d.summary.find((s) => s.nickname === "nova")?.water).toBe("Вода нет данных");
    deps.advance(-10 * 24 * 3600 * 1000);
  });

  it("waterKeyUsesTheStaffDayAroundMidnight", async () => {
    const t = await seedCardTeam(deps);
    await putDoc(deps, t.ids.vega, "nutrition", { meals: [], water: { "2026-10-26": 700, "2026-10-27": 1500 } });
    deps.advance(9 * 3600 * 1000 + 30 * 60 * 1000); // 21:30 UTC = 00:30 on the 27th in Moscow
    const d = await load(t);
    expect(d.summary.find((s) => s.nickname === "vega")?.water).toBe("Вода 1,5 / 2,5 л");
    deps.advance(-(9 * 3600 * 1000 + 30 * 60 * 1000));
  });

  it("summarySubNamesTodayAndFooterTheNorm", async () => {
    const d = await load(await seed());
    expect(d.summarySub).toBe("Сегодня, 26 октября. Нажмите на игрока, чтобы показать только его записи");
  });
});

describe("dialog", () => {
  it("opensAMealOfTheRosterInRange", async () => {
    const t = await seed();
    const d = await load(t, { meal: PASTA.toUpperCase(), days: "7", who: t.ids.nova });
    expect(d.params.meal).toBe(PASTA);
    expect(d.dialog).toEqual({
      nickname: "vega",
      source: { label: "Вручную", tone: "mute" },
      name: "Паста",
      when: "Сегодня, 13:30",
      photo: null,
      tiles: [
        { label: "Ккал", value: "687" },
        { label: "Белки", value: "31 г" },
        { label: "Жиры", value: "22 г" },
        { label: "Углев.", value: "88 г" },
      ],
      closeHref: `/staff/players?section=nutrition&who=${t.ids.nova}&days=7`,
    });
  });

  it("sourcesAndOlderDates", async () => {
    const t = await seed();
    expect((await load(t, { meal: OATS }))?.dialog?.source).toEqual({ label: "AI-анализ фото", tone: "ok" });
    expect((await load(t, { meal: OATS }))?.dialog?.photo).toBe(`/staff/photos/${t.ids.vega}/${OATS}`);
    expect((await load(t, { meal: STEAK }))?.dialog?.source).toEqual({ label: "Быстрое добавление", tone: "mute" });
    expect((await load(t, { meal: STEAK }))?.dialog?.when).toBe("Вчера, 19:00");
    expect((await load(t, { meal: OLD, days: "7" }))?.dialog?.when).toBe("22 октября, 12:00");
  });

  it("dishDialogForForeignMealIdIsClosed", async () => {
    const t = await seed();
    for (const meal of [FOREIGN, UNKNOWN, "not-a-guid", `${PASTA}' or 1=1`]) {
      const d = await load(t, { meal });
      expect(d.dialog, meal).toBeNull();
      expect(JSON.stringify(d), meal).not.toContain("Чужое блюдо");
    }
  });

  it("aMealOutsideThePeriodIsClosed", async () => {
    const t = await seed();
    expect((await load(t, { meal: OLD })).dialog).toBeNull(); // 3 days: 24th-26th
    expect((await load(t, { meal: OLD, days: "7" })).dialog).not.toBeNull();
    expect((await load(t, { meal: STEAK, days: "1" })).dialog).toBeNull();
  });

  it("aMealOfARemovedPlayerIsClosed", async () => {
    const t = await seed();
    await removeMember(deps, t.ids.team, t.ids.staff, t.ids.nova);
    expect((await load(t, { meal: NOVA_MEAL })).dialog).toBeNull();
  });
});

describe("roster size", () => {
  it("capsThePlayersAtTwelveAndSaysSo", async () => {
    const t = await seedCardTeam(deps);
    for (let i = 0; i < 11; i += 1) {
      await deps.sql`
        insert into users (email, nickname, password_hash, role, team_id, email_verified_at,
          accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at, created_at, updated_at)
        values (${`extra${i}@n.gg`}, ${`extra${i}`}, 'x', 'teamPlayer', ${t.ids.team}, ${deps.now()},
          ${deps.now()}, ${deps.now()}, '2026-10-26', ${deps.now()}, ${deps.now()}, ${deps.now()})`;
      deps.advance(1_000);
    }
    const d = await load(t);
    expect(MAX_PLAYERS).toBe(12);
    expect(d.players).toHaveLength(12);
    expect(d.summary).toHaveLength(12);
    expect(d.hidden).toBe(2);
    expect(d.players.map((p) => p.nickname).slice(0, 3)).toEqual(["vega", "nova", "ghost"]);
  });

  it("anEmptyTeamHasNoPlayersAndNoFeed", async () => {
    const t = await seedCardTeam(deps);
    for (const id of [t.ids.vega, t.ids.nova, t.ids.ghost]) await removeMember(deps, t.ids.team, t.ids.staff, id);
    const d = await load(t);
    expect(d.players).toEqual([]);
    expect(d.groups).toEqual([]);
    expect(d.summary).toEqual([]);
    expect(d.dialog).toBeNull();
  });
});
