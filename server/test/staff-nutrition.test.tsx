import { renderToStaticMarkup } from "react-dom/server";
import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { NutritionFeed } from "@/components/staff/players/NutritionFeed";
import { removeMember } from "../lib/team/members";
import { loadNutrition } from "../lib/staff/nutrition";
import { resetDb, testDeps } from "./helpers";
import { putDoc, seedCardTeam, type CardTeam } from "./staff-card-fixture";

const deps = testDeps();
const TZ = "Europe/Moscow";

beforeEach(async () => {
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

const PASTA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1";
const OATS = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2";
const NOVA_MEAL = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1";

function text(markup: string): string {
  return markup.replace(/<[^>]*>/g, " ").replace(/ /g, " ").replace(/\s+/g, " ").trim();
}

function hrefs(markup: string): string[] {
  return [...markup.matchAll(/href="([^"]*)"/g)].map((m) => m[1].replaceAll("&amp;", "&"));
}

async function seed(): Promise<CardTeam> {
  const t = await seedCardTeam(deps);
  await putDoc(deps, t.ids.vega, "nutrition", {
    meals: [
      { id: OATS, date: "2026-10-26T06:00:00Z", name: "Овсянка", kcal: 340, protein: 11, fat: 8, carbs: 56, source: "photo", photoRef: true },
      { id: PASTA, date: "2026-10-26T10:30:00Z", name: "Паста", kcal: 687, protein: 31, fat: 22, carbs: 88, source: "manual" },
    ],
    water: { "2026-10-26": 1800 },
  });
  await putDoc(deps, t.ids.nova, "nutrition", {
    meals: [{ id: NOVA_MEAL, date: "2026-10-25T08:00:00Z", name: "Курица", kcal: 528, source: "quick" }],
  });
  return t;
}

async function render(t: CardTeam, query: Record<string, string> = {}) {
  const data = await loadNutrition(deps, t.ctx, { section: "nutrition", ...query }, TZ);
  return { markup: renderToStaticMarkup(<NutritionFeed data={data} />), data };
}

describe("filter bar", () => {
  it("sectionKeepsPlayerFilterLinks", async () => {
    const t = await seed();
    const { markup } = await render(t, { days: "7", photos: "1" });
    const links = hrefs(markup);
    expect(links).toContain("/staff/players?section=nutrition&days=7&photos=1"); // «Все игроки»
    expect(links).toContain(`/staff/players?section=nutrition&who=${t.ids.vega}&days=7&photos=1`);
    expect(links).toContain(`/staff/players?section=nutrition&who=${t.ids.nova}&days=7&photos=1`);
    // the period chips keep the player and the photo filter, «Только с фото» keeps the player and the period
    const who = await render(t, { who: t.ids.nova, photos: "1" });
    expect(hrefs(who.markup)).toContain(`/staff/players?section=nutrition&who=${t.ids.nova}&days=1&photos=1`);
    expect(hrefs(who.markup)).toContain(`/staff/players?section=nutrition&who=${t.ids.nova}`); // photo filter off
    const noPhoto = await render(t, { who: t.ids.nova });
    expect(hrefs(noPhoto.markup)).toContain(`/staff/players?section=nutrition&who=${t.ids.nova}&photos=1`);
  });

  it("chipsNameThePlayersAndPeriodsAndMarkTheCurrent", async () => {
    const t = await seed();
    const { markup } = await render(t);
    const words = text(markup);
    expect(markup).toContain('role="group" aria-label="Игрок"');
    expect(markup).toContain('role="group" aria-label="Период"');
    for (const label of ["Все игроки", "vega", "nova", "ghost", "Сегодня", "3 дня", "7 дней", "Только с фото"]) {
      expect(words).toContain(label);
    }
    expect(markup).not.toContain("zed-outsider");
    expect(markup).not.toContain("solo-loner");
    // exactly: all players, 3 days (and not the photo filter) are current
    const current = [...markup.matchAll(/<a[^>]*aria-current="true"[^>]*>(.*?)<\/a>/g)].map((m) => text(m[1]));
    expect(current.filter((c) => !c.includes("Состав"))).toEqual(expect.arrayContaining(["Все игроки", "3 дня"]));
    expect(current).not.toContain("Только с фото");
  });
});

describe("feed", () => {
  it("cardsLinkToTheDialogAndShowPhotosOnlyWithPhotoRef", async () => {
    const t = await seed();
    const { markup, data } = await render(t);
    expect(data.groups).toHaveLength(2); // today (vega) and yesterday (nova)
    const words = text(markup);
    expect(words).toContain("Сегодня, 26 октября, понедельник");
    expect(words).toContain("2 записи");
    expect(words).toContain("687 ккал · 13:30");
    expect(words).toContain("340 ккал · 09:00");
    expect(words).toContain("3 записи · 1 с фото");
    expect(hrefs(markup)).toContain(`/staff/players?section=nutrition&meal=${PASTA}`);
    // only the meal with photoRef has a thumbnail, through the staff route
    const images = [...markup.matchAll(/<img[^>]*src="([^"]*)"/g)].map((m) => m[1]);
    expect(images).toEqual([`/staff/photos/${t.ids.vega}/${OATS}`]);
    expect(words).toContain("без фото");
  });

  it("emptyFeedMessage", async () => {
    const t = await seed();
    const { markup } = await render(t, { who: t.ids.ghost });
    expect(text(markup)).toContain("Записей нет. Попробуйте выключить «Только с фото» или выбрать другой период");
    expect(text(markup)).toContain("0 записей · 0 с фото · ghost");
    const filled = await render(t);
    expect(text(filled.markup)).not.toContain("Записей нет.");
  });

  it("noDemoMealsInvented", async () => {
    const t = await seedCardTeam(deps);
    const { markup } = await render(t);
    const words = text(markup);
    expect(words).toContain("Записей нет.");
    expect(words).not.toMatch(/vexa|frost|Kairo|zeNN|Овсянка с ягодами|Atlant/);
    expect(markup).not.toContain("<img");
  });
});

describe("summary", () => {
  it("showsTodayPerPlayerWithGoalStatusMacrosAndWater", async () => {
    const t = await seed();
    const { markup } = await render(t);
    const words = text(markup);
    expect(words).toContain("Сводка за день");
    expect(words).toContain("Сегодня, 26 октября. Нажмите на игрока, чтобы показать только его записи");
    expect(words).toContain("1 027 / 2 400 ккал");
    expect(words).toContain("2 приёма пищи");
    expect(words).toContain("В нор" + "ме");
    expect(words).toContain("Б 42 г");
    expect(words).toContain("Ж 30 г");
    expect(words).toContain("У 144 г");
    expect(words).toContain("Вода 1,8 / 2,5 л");
    expect(words).toContain("Вода нет данных");
    expect(words).toContain("Нет записей"); // ghost
    expect(words).toContain("0 / 2 400 ккал");
    expect(words).toContain("Норма — из настроек игрока (по умолчанию 2 400 ккал)");
    expect(markup).toMatch(/width:\s*43%/);
  });

  it("rowsAreLinksThatToggleThePlayer", async () => {
    const t = await seed();
    const { markup } = await render(t, { who: t.ids.vega });
    expect(hrefs(markup)).toContain("/staff/players?section=nutrition"); // vega's row (selected) goes back to all
    expect(hrefs(markup)).toContain(`/staff/players?section=nutrition&who=${t.ids.nova}`);
  });
});

describe("dish dialog", () => {
  it("rendersThePhotoPillsTilesAndACloseLink", async () => {
    const t = await seed();
    const { markup } = await render(t, { meal: OATS, days: "7" });
    expect(markup).toContain('role="dialog"');
    expect(markup).toContain('aria-modal="true"');
    const dialog = markup.slice(markup.indexOf('role="dialog"'));
    const words = text(dialog);
    for (const part of ["vega", "AI-анализ фото", "Овсянка", "Сегодня, 09:00", "Ккал", "Белки", "Жиры", "Углев.", "340", "11 г", "8 г", "56 г"]) {
      expect(words).toContain(part);
    }
    expect(dialog).toContain(`src="/staff/photos/${t.ids.vega}/${OATS}"`);
    expect(dialog).toMatch(/<a[^>]*aria-label="Закрыть"[^>]*>/);
    // close = the same view without `meal`
    const close = /<a[^>]*href="([^"]*)"[^>]*aria-label="Закрыть"|<a[^>]*aria-label="Закрыть"[^>]*href="([^"]*)"/.exec(dialog);
    expect((close?.[1] ?? close?.[2] ?? "").replaceAll("&amp;", "&")).toBe("/staff/players?section=nutrition&days=7");
  });

  it("manualAndQuickSourcesHaveTheirOwnLabels", async () => {
    const t = await seed();
    expect(text((await render(t, { meal: PASTA })).markup)).toContain("Вручную");
    expect(text((await render(t, { meal: NOVA_MEAL })).markup)).toContain("Быстрое добавление");
  });

  it("dishDialogForForeignMealIdIsClosed", async () => {
    const t = await seed();
    const foreign = "cccccccc-cccc-4ccc-8ccc-ccccccccccc1";
    await putDoc(deps, t.ids.outsider, "nutrition", {
      meals: [{ id: foreign, date: "2026-10-26T08:00:00Z", name: "Чужое блюдо", kcal: 999 }],
    });
    const { markup } = await render(t, { meal: foreign });
    expect(markup).not.toContain('role="dialog"');
    expect(markup).not.toContain("Чужое блюдо");
    expect((await render(t)).markup).not.toContain('role="dialog"');
  });

  it("noCommentFieldInDialog", async () => {
    const t = await seed();
    const { markup } = await render(t, { meal: PASTA });
    expect(markup).toContain('role="dialog"');
    expect(markup).not.toContain("<textarea");
    expect(markup).not.toContain("<form");
    expect(markup).not.toContain("<input");
    expect(text(markup)).not.toContain("Комментарий");
    expect(text(markup)).not.toContain("Отправить");
  });
});

describe("page frame", () => {
  it("segmentsLinkBackToTheRosterAndMarkTheCurrentSection", async () => {
    const t = await seed();
    const { markup } = await render(t);
    expect(hrefs(markup)).toContain("/staff/players");
    expect(markup).toMatch(/<span[^>]*aria-current="page"[^>]*>[^<]*Питание команды/);
    expect(markup).toMatch(/<a[^>]*href="\/staff\/players"[^>]*>[^<]*Состав и карточки/);
    expect(text(markup)).not.toContain("Скоро");
  });

  it("saysWhenOnlyTheFirstTwelvePlayersAreShown", async () => {
    const t = await seedCardTeam(deps);
    for (let i = 0; i < 9; i += 1) {
      await deps.sql`
        insert into users (email, nickname, password_hash, role, team_id, email_verified_at,
          accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at, created_at, updated_at)
        values (${`more${i}@n.gg`}, ${`more${i}`}, 'x', 'teamPlayer', ${t.ids.team}, ${deps.now()},
          ${deps.now()}, ${deps.now()}, '2026-10-26', ${deps.now()}, ${deps.now()}, ${deps.now()})`;
      deps.advance(1_000);
    }
    expect(text((await render(t)).markup)).not.toContain("Показаны первые 12 игроков");
    await deps.sql`
      insert into users (email, nickname, password_hash, role, team_id, email_verified_at,
        accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at, created_at, updated_at)
      values ('last@n.gg', 'last', 'x', 'teamPlayer', ${t.ids.team}, ${deps.now()},
        ${deps.now()}, ${deps.now()}, '2026-10-26', ${deps.now()}, ${deps.now()}, ${deps.now()})`;
    const { markup } = await render(t);
    expect(text(markup)).toContain("Показаны первые 12 игроков");
    expect(markup).not.toContain(">last<");
  });

  it("anEmptyTeamSaysSoInsteadOfAnEmptyFeed", async () => {
    const t = await seedCardTeam(deps);
    for (const id of [t.ids.vega, t.ids.nova, t.ids.ghost]) await removeMember(deps, t.ids.team, t.ids.staff, id);
    const { markup } = await render(t);
    expect(text(markup)).toContain("В команде пока нет игроков");
    expect(text(markup)).not.toContain("Записей нет.");
    expect(text(markup)).not.toContain("Сводка за день");
  });
});
