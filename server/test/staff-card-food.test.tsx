import { renderToStaticMarkup } from "react-dom/server";
import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { CardFrame } from "@/components/staff/card/CardFrame";
import { loadCard, type CardData } from "../lib/staff/card";
import { resetDb, testDeps } from "./helpers";
import { putDoc, seedCardTeam, type CardTeam } from "./staff-card-fixture";

const deps = testDeps();
const TZ = "Europe/Moscow";

beforeEach(async () => {
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

function text(markup: string): string {
  return markup.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim();
}

function hrefs(markup: string): string[] {
  return [...markup.matchAll(/href="([^"]*)"/g)].map((m) => m[1].replaceAll("&amp;", "&"));
}

function card(data: CardData): Extract<CardData, { kind: "card" }> {
  if (data.kind !== "card") throw new Error("expected a card");
  return data;
}

async function frame(t: CardTeam, query: Record<string, string>) {
  const data = card(await loadCard(deps, t.ctx, query, TZ));
  return { markup: renderToStaticMarkup(<CardFrame card={data} />), data };
}

// The fixed test clock is Monday 2026-10-26, 15:00 in Moscow. The phone day of an entry is the UTC date of
// (instant + 12 h): midnight in a Moscow phone, 21:00 UTC the evening before, is the next calendar day.
const OCT24 = "2026-10-23T21:00:00Z";
const OCT22 = "2026-10-21T21:00:00Z";

async function seedWheel(t: CardTeam): Promise<void> {
  await putDoc(deps, t.ids.nova, "wellbeing", {
    balance: [
      // superseded by the later entry of the same day
      { id: "b0", day: OCT24, values: { physical: 2, emotional: 2 } },
      // `social` is missing: the app counts 5.0
      {
        id: "b1",
        day: OCT24,
        values: { physical: 8, emotional: 6, intellectual: 7, spiritual: 4, professional: 9, environment: 3, financial: 10 },
      },
      { id: "b2", day: OCT22, values: { physical: 10 } },
    ],
  });
}

const FULL_NAMES = [
  "Физическое здоровье",
  "Эмоциональное состояние",
  "Интеллектуальное развитие",
  "Духовное развитие",
  "Профессиональная сфера",
  "Социальные отношения",
  "Окружающая среда",
  "Финансовое благополучие",
];
const SHORT_NAMES = ["Здоровье", "Эмоции", "Интеллект", "Духовность", "Профессия", "Отношения", "Среда", "Финансы"];

describe("wheel tab", () => {
  it("wheelDayWithoutEntryShowsEmpty", async () => {
    const t = await seedCardTeam(deps);
    await seedWheel(t);
    // today (26 Oct) has no entry: the board's empty state, no radar
    const today = await frame(t, { player: t.ids.nova, tab: "wheel" });
    const words = text(today.markup);
    expect(words).toContain("Игрок не заполнил колесо баланса в этот день");
    expect(today.markup).not.toContain('viewBox="0 0 340 340"');
    expect(words).not.toContain("Физическое здоровье");
    // the seven chips: today first, then the six days before, ` · —` where nothing was filled in
    const group = today.markup.slice(today.markup.indexOf('aria-label="День"'));
    const chips = [...group.matchAll(/<a [^>]*>([^<]*)<\/a>/g)].map((m) => m[1]).slice(0, 7);
    expect(chips).toEqual([
      "Сегодня · —",
      "вс 25 · —",
      "сб 24",
      "пт 23 · —",
      "чт 22",
      "ср 21 · —",
      "вт 20 · —",
    ]);
    expect(today.markup).toMatch(/aria-current="date"[^>]*>Сегодня · —</);

    // a player who never filled anything in: every chip is empty, no invented values
    const ghost = await frame(t, { player: t.ids.ghost, tab: "wheel" });
    expect(text(ghost.markup)).toContain("Игрок не заполнил колесо баланса в этот день");
    expect(ghost.markup).not.toContain("<polygon");
  });

  it("chipsLinkToTheDayAndKeepTheRestOfTheSelection", async () => {
    const t = await seedCardTeam(deps);
    await seedWheel(t);
    const { markup } = await frame(t, { player: t.ids.nova, tab: "wheel", period: "7" });
    const group = markup.slice(markup.indexOf('aria-label="День"'));
    const links = hrefs(group).slice(0, 7);
    const base = `/staff/players?player=${t.ids.nova}&period=7&tab=wheel`;
    expect(links).toEqual([
      base,
      `${base}&day=2026-10-25`,
      `${base}&day=2026-10-24`,
      `${base}&day=2026-10-23`,
      `${base}&day=2026-10-22`,
      `${base}&day=2026-10-21`,
      `${base}&day=2026-10-20`,
    ]);
  });

  it("wheelUsesAppLabels", async () => {
    const t = await seedCardTeam(deps);
    await seedWheel(t);
    const { markup, data } = await frame(t, { player: t.ids.nova, tab: "wheel", day: "2026-10-24" });
    const words = text(markup);
    // the list: full names with the day's values, the later of two entries wins, a missing key is 5
    expect(words).toContain(
      "Физическое здоровье 8 Эмоциональное состояние 6 Интеллектуальное развитие 7 Духовное развитие 4 " +
        "Профессиональная сфера 9 Социальные отношения 5 Окружающая среда 3 Финансовое благополучие 10",
    );
    for (const name of FULL_NAMES) expect(words, name).toContain(name);
    // short labels around the radar are the app's, not the board's
    const labels = [...markup.matchAll(/<span class="[^"]*rl[^"]*" style="([^"]*)">([^<]*)<\/span>/g)];
    expect(labels.map((m) => m[2])).toEqual(SHORT_NAMES);
    expect(markup).not.toContain("Карьера");
    expect(markup).not.toContain("Окружение");
    // label positions: radius + 30 (x) / radius + 22 (y) as a share of 340; the first one is straight up
    expect(labels[0][1]).toBe("left:50.0%;top:9.7%");
    expect(labels[2][1]).toBe("left:92.6%;top:50.0%");
    // aria label of the figure names the short labels and the values
    expect(markup).toContain(
      'aria-label="Колесо баланса: Здоровье 8, Эмоции 6, Интеллект 7, Духовность 4, Профессия 9, Отношения 5, Среда 3, Финансы 10"',
    );
    // the chip of the chosen day is current
    expect(markup).toMatch(/aria-current="date"[^>]*>сб 24</);
    expect(data.wheel.selected).toBe("2026-10-24");
  });

  it("radarGeometryFromTheBoard", async () => {
    const t = await seedCardTeam(deps);
    await seedWheel(t);
    const { markup } = await frame(t, { player: t.ids.nova, tab: "wheel", day: "2026-10-24" });
    expect(markup).toContain('viewBox="0 0 340 340"');
    const polygons = [...markup.matchAll(/<polygon points="([^"]*)"/g)].map((m) => m[1]);
    // four rings (2.5, 5, 7.5, 10 of 10), the player's polygon, and eight invisible sectors the pointer picks
    expect(polygons).toHaveLength(13);
    const points = (s: string) => s.split(" ").map((p) => p.split(",").map(Number));
    // ring 10: radius 115 around (170, 170), spokes from straight up every 45 degrees
    expect(polygons[3].startsWith("170.0,55.0 ")).toBe(true);
    expect(points(polygons[3])[2]).toEqual([285, 170]);
    expect(points(polygons[3])[4]).toEqual([170, 285]);
    expect(points(polygons[3])).toHaveLength(8);
    // ring 2.5: a quarter of the radius
    expect(polygons[0].startsWith("170.0,141.3 ")).toBe(true);
    expect(points(polygons[1])[0]).toEqual([170, 112.5]);
    // the player's values: physical 8 straight up, intellectual 7 straight right
    expect(polygons[4].startsWith("170.0,78.0 ")).toBe(true);
    expect(points(polygons[4])[2]).toEqual([250.5, 170]);
    // the spokes
    expect(markup).toContain('d="M170 170 L170.0 55.0 M170 170 L251.3 88.7 M170 170 L285.0 170.0');
  });

  it("dayOutsideTheSevenChipsIsToday", async () => {
    const t = await seedCardTeam(deps);
    await seedWheel(t);
    const { markup, data } = await frame(t, { player: t.ids.nova, tab: "wheel", day: "2026-10-10" });
    expect(data.wheel.selected).toBe("2026-10-26");
    expect(text(markup)).toContain("Игрок не заполнил колесо баланса в этот день");
  });

  it("onlyTheChosenDaysEntryIsShown", async () => {
    const t = await seedCardTeam(deps);
    await seedWheel(t);
    const { markup } = await frame(t, { player: t.ids.nova, tab: "wheel", day: "2026-10-22" });
    const words = text(markup);
    // 22 Oct has physical 10 and everything else counts as the app's 5
    expect(words).toContain("Физическое здоровье 10 Эмоциональное состояние 5 Интеллектуальное развитие 5");
  });
});

const GUID_OATS = "11111111-1111-4111-8111-111111111111";
const GUID_PASTA = "22222222-2222-4222-8222-222222222222";
const GUID_OMELETTE = "33333333-3333-4333-8333-333333333333";

async function seedMeals(t: CardTeam): Promise<void> {
  await putDoc(deps, t.ids.nova, "nutrition", {
    meals: [
      { id: GUID_OATS, date: "2026-10-26T10:05:00Z", name: "Овсянка", kcal: 400, protein: 12, fat: 6, carbs: 60, source: "photo", photoRef: true },
      { id: GUID_PASTA, date: "2026-10-26T16:00:00Z", name: "Паста", kcal: 600, protein: 20, fat: 18, carbs: 80, source: "manual" },
      { id: GUID_OMELETTE, date: "2026-10-25T09:30:00Z", name: "Омлет", kcal: 500, protein: 30, fat: 30, carbs: 4, source: "manual", photoRef: false },
      // claims a photo but its id is not one the photo store can hold: no image
      { id: "meal-x", date: "2026-10-20T08:15:00Z", name: "Гречка", kcal: 700, protein: 25, fat: 8, carbs: 120, source: "photo", photoRef: true },
      // outside the 30-day period
      { id: "44444444-4444-4444-8444-444444444444", date: "2026-08-01T08:00:00Z", name: "Старый обед", kcal: 9000, protein: 1, fat: 1, carbs: 1, source: "manual" },
    ],
  });
}

describe("food tab", () => {
  it("foodAveragesOverDaysWithMeals", async () => {
    const t = await seedCardTeam(deps);
    await seedMeals(t);
    const { markup, data } = await frame(t, { player: t.ids.nova, tab: "food" });
    // 4 meals in 30 days on 3 days: (1000 + 500 + 700) / 3, not / 30 and not / 7
    expect(data.food.summary).toBe("Записей: 4 · в среднем 733 ккал в день");
    const words = text(markup);
    expect(words).toContain("Записей: 4 · в среднем 733 ккал в день");
    // the table of the board, newest first, day label + time in the staff zone
    expect(markup).toContain('aria-label="Дневник питания"');
    expect(words).toContain("Фото Дата Блюдо Калорийность");
    expect(words).toContain("Сегодня, 19:00 Паста 600 ккал Сегодня, 13:05 Овсянка 400 ккал Вчера, 12:30 Омлет 500 ккал 20 окт, 11:15 Гречка 700 ккал");
    expect(words).not.toContain("Старый обед");
    expect(markup.match(/role="row"/g)).toHaveLength(5); // header + 4 meals
  });

  it("averageIsOverTheChosenPeriod", async () => {
    const t = await seedCardTeam(deps);
    await seedMeals(t);
    // 7 days (20..26 Oct) still holds all four; 90 days adds the August lunch: (1000 + 500 + 700 + 9000) / 4
    const week = await frame(t, { player: t.ids.nova, tab: "food", period: "7" });
    expect(week.data.food.summary).toBe("Записей: 4 · в среднем 733 ккал в день");
    const quarter = await frame(t, { player: t.ids.nova, tab: "food", period: "90" });
    expect(quarter.data.food.summary).toBe("Записей: 5 · в среднем 2800 ккал в день");
  });

  it("foodThumbUsesPhotoRouteOnlyWithPhotoRef", async () => {
    const t = await seedCardTeam(deps);
    await seedMeals(t);
    const { markup } = await frame(t, { player: t.ids.nova, tab: "food" });
    const images = [...markup.matchAll(/<img [^>]*>/g)].map((m) => m[0]);
    expect(images).toHaveLength(1);
    expect(images[0]).toContain(`src="/staff/photos/${t.ids.nova}/${GUID_OATS}"`);
    expect(images[0]).toContain('alt="Фото блюда"');
    expect(markup).not.toContain(GUID_PASTA);
    expect(markup).not.toContain(GUID_OMELETTE);
    expect(markup).not.toContain("meal-x");
    // the three other meals get the board's placeholder
    expect(markup.match(/role="img" aria-label="Фото блюда"/g)).toHaveLength(3);
  });

  it("foodWithoutMealsHasNoNumbers", async () => {
    const t = await seedCardTeam(deps);
    const { markup, data } = await frame(t, { player: t.ids.vega, tab: "food" });
    expect(data.food.summary).toBe("Записей: 0 · в среднем нет данных");
    const words = text(markup);
    expect(words).toContain("нет данных");
    expect(words).not.toMatch(/NaN|undefined/);
    expect(markup).not.toContain("<img");
  });

  it("foodRowsAreCappedAtThirty", async () => {
    const t = await seedCardTeam(deps);
    const meals = Array.from({ length: 35 }, (_, i) => ({
      id: `${String(i + 10).padStart(8, "0")}-0000-4000-8000-000000000000`,
      date: new Date(Date.UTC(2026, 9, 26, 9, 0) - i * 3_600_000).toISOString(),
      name: `Блюдо ${i}`,
      kcal: 100,
    }));
    await putDoc(deps, t.ids.nova, "nutrition", { meals });
    const { markup, data } = await frame(t, { player: t.ids.nova, tab: "food" });
    expect(data.food.rows).toHaveLength(30);
    expect(markup.match(/role="row"/g)).toHaveLength(31);
    expect(text(markup)).toContain("Показаны последние 30 из 35");
    // the summary still counts every meal of the period
    expect(data.food.summary.startsWith("Записей: 35 ·")).toBe(true);
  });

  it("malformedMealsAreSkippedNotInvented", async () => {
    const t = await seedCardTeam(deps);
    await putDoc(deps, t.ids.nova, "nutrition", {
      meals: [
        { id: GUID_OATS, date: "not a date", name: "Сломанная дата", kcal: 100 },
        { id: GUID_PASTA, date: "2026-10-26T10:00:00Z", name: "Без калорий" },
        { id: GUID_OMELETTE, date: "2026-10-26T11:00:00Z", name: "Минус", kcal: -5 },
        "garbage",
        null,
      ],
    });
    const { markup, data } = await frame(t, { player: t.ids.nova, tab: "food" });
    // dated meals are listed; the ones without a valid kcal show «—» and add nothing to the average
    expect(data.food.rows.map((r) => r.name)).toEqual(["Минус", "Без калорий"]);
    expect(data.food.rows.map((r) => r.kcal)).toEqual(["—", "—"]);
    expect(data.food.summary).toBe("Записей: 2 · в среднем нет данных");
    expect(text(markup)).not.toMatch(/NaN|undefined/);
  });

  it("foreignPlayersPhotosNeverAppearInAnotherCard", async () => {
    const t = await seedCardTeam(deps);
    await seedMeals(t);
    await putDoc(deps, t.ids.outsider, "nutrition", {
      meals: [{ id: GUID_PASTA, date: "2026-10-26T10:00:00Z", name: "Чужое блюдо", kcal: 1, photoRef: true }],
    });
    const { markup } = await frame(t, { player: t.ids.outsider, tab: "food" });
    // an outsider's id is not on the roster: the card is the first roster player's, and holds no foreign data
    expect(markup).not.toContain("Чужое блюдо");
    expect(markup).not.toContain(t.ids.outsider);
  });
});
