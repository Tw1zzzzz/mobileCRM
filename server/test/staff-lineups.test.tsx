import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { HeaderActions } from "@/components/staff/lineups/HeaderActions";
import { LineupsView } from "@/components/staff/lineups/LineupsView";
import { lineupsHref } from "@/components/staff/lineups/meta";
import type { LineupFilter, StaffLineup, StaffLineupsPage } from "@/lib/staff/lineups";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: () => undefined, replace: () => undefined, refresh: () => undefined, prefetch: () => undefined }),
  usePathname: () => "/staff/lineups",
  useSearchParams: () => new URLSearchParams(),
}));

const FILTERS: LineupFilter = { q: "", map: "all", type: "all", side: "all", favorites: false, entry: null };

function entry(over: Partial<StaffLineup> = {}): StaffLineup {
  return {
    id: "11111111-1111-4111-8111-111111111111", title: "Смок в окно", map: "mirage", side: "t", type: "smoke",
    details: "Встать у ящика\n\nПрицел на антенну", videoUrl: "https://example.com/v", author: "coach",
    createdAt: new Date("2026-10-25T10:00:00Z"), favorite: false, ...over,
  };
}

function pageOf(over: Partial<StaffLineupsPage> = {}): StaffLineupsPage {
  const entries = over.entries ?? [entry()];
  return {
    filters: FILTERS, entries, selected: entries[0] ?? null, totalForTeam: entries.length,
    stats: { total: entries.length, lineups: entries.length, scripts: 0, favorites: 0 },
    mapCounts: { all: entries.length, mirage: entries.length, inferno: 0, nuke: 0, ancient: 0, anubis: 0, dust2: 0, train: 0 },
    today: "2026-10-26", form: null, hasMore: false, error: null, notice: null, ...over,
  };
}

/** Whether the radio `name=value` is the checked one (attribute order is React's business, not the test's). */
function checked(markup: string, name: string, value: string): boolean {
  return new RegExp(`<input(?=[^>]*name="${name}")(?=[^>]*value="${value}")(?=[^>]*checked)[^>]*>`).test(markup);
}

function text(markup: string): string {
  return markup.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ");
}

describe("lineups page", () => {
  it("showsTheFourCountersOfTheTeam", () => {
    const t = text(renderToStaticMarkup(<LineupsView page={pageOf({ stats: { total: 9, lineups: 6, scripts: 2, favorites: 3 } })} />));
    expect(t).toContain("Всего записей 9");
    expect(t).toContain("Раскидок 6");
    expect(t).toContain("Скриптов 2");
    expect(t).toContain("В избранном 3");
  });

  it("filterPanelHasTheBoardsChipsAndCounts", () => {
    const markup = renderToStaticMarkup(<LineupsView page={pageOf()} />);
    const t = text(markup);
    expect(t).toContain("Все 1");
    for (const name of ["Mirage", "Inferno", "Nuke", "Ancient", "Anubis", "Dust2", "Train"]) expect(t).toContain(name);
    for (const name of ["Все типы", "Смок", "Флешка", "Молотов", "HE", "Скрипт", "Заметка", "Обе", "★ Избранное"]) expect(t).toContain(name);
    expect(markup).toContain('placeholder="Поиск: название, карта, описание"');
    expect(markup).toContain('href="/staff/lineups?map=inferno"');
    expect(markup).toContain('href="/staff/lineups?side=ct"');
    expect(markup).toContain('href="/staff/lineups?fav=1"');
  });

  it("chipsKeepTheOtherFiltersInTheUrl", () => {
    const filters: LineupFilter = { ...FILTERS, q: "смок", map: "nuke", side: "t", favorites: true };
    const markup = renderToStaticMarkup(<LineupsView page={pageOf({ filters })} />);
    expect(markup).toContain('href="/staff/lineups?q=%D1%81%D0%BC%D0%BE%D0%BA&amp;map=nuke&amp;type=smoke&amp;side=t&amp;fav=1"');
    expect(markup).toMatch(/<a[^>]*aria-current="true"[^>]*>Nuke/);
    expect(text(markup)).toContain("1 запись на Nuke");
  });

  it("cardsShowTypeSideStarVideoAndByline", () => {
    const markup = renderToStaticMarkup(<LineupsView page={pageOf({ entries: [entry({ favorite: true })] })} />);
    const t = text(markup);
    expect(t).toContain("Смок в окно");
    expect(t).toContain("Mirage · T · Смок");
    expect(t).toContain("coach · вчера");
    expect(t).toContain("▶ Видео");
    expect(markup).toContain('aria-label="Убрать из избранного"');
  });

  it("cardWithoutVideoHasNoVideoBadge", () => {
    const markup = renderToStaticMarkup(<LineupsView page={pageOf({ entries: [entry({ videoUrl: null })] })} />);
    expect(text(markup)).not.toContain("▶ Видео");
    expect(text(markup)).toContain("Видео не добавлено");
    expect(text(markup)).not.toContain("Смотреть видео");
  });

  it("detailListsNumberedStepsAndTheByline", () => {
    const t = text(renderToStaticMarkup(<LineupsView page={pageOf()} />));
    expect(t).toContain("Как бросать");
    expect(t).toContain("1 Встать у ящика");
    expect(t).toContain("2 Прицел на антенну");
    expect(t).toContain("Добавил coach · вчера · видно всем игрокам команды");
    expect(t).toContain("Смотреть видео");
    expect(t).toContain("Атака (T)");
    expect(t).toContain("☆ В избранное");
    expect(t).toContain("Редактировать");
    expect(t).toContain("Удалить");
  });

  it("scriptsGetScriptStepsAndCtIsDefence", () => {
    const script = entry({ type: "script", side: "ct" });
    const t = text(renderToStaticMarkup(<LineupsView page={pageOf({ entries: [script] })} />));
    expect(t).toContain("Шаги скрипта");
    expect(t).toContain("Защита (CT)");
    expect(t).not.toContain("Как бросать");
  });

  it("favouriteEntryOffersToRemoveIt", () => {
    const t = text(renderToStaticMarkup(<LineupsView page={pageOf({ entries: [entry({ favorite: true })] })} />));
    expect(t).toContain("★ В избранном");
  });

  it("emptyListAsksToResetTheFilters", () => {
    const markup = renderToStaticMarkup(<LineupsView page={pageOf({ entries: [] })} />);
    const t = text(markup);
    expect(t).toContain("Ничего не найдено. Сбросьте фильтры или добавьте запись");
    expect(t).toContain("Выберите запись, чтобы посмотреть детали");
    expect(markup).toMatch(/<a[^>]*href="\/staff\/lineups"[^>]*>Сбросить фильтры<\/a>/);
    expect(t).toContain("0 записей");
  });

  it("theOpenEntryIsMarked", () => {
    const second = entry({ id: "22222222-2222-4222-8222-222222222222", title: "Флешка" });
    const markup = renderToStaticMarkup(<LineupsView page={pageOf({ entries: [entry(), second], selected: second })} />);
    expect(markup).toMatch(/aria-current="true"[^>]*href="\/staff\/lineups\?entry=2222/);
  });

  it("noDemoDataInTheMarkup", () => {
    const markup = renderToStaticMarkup(<LineupsView page={pageOf({ entries: [], stats: { total: 0, lineups: 0, scripts: 0, favorites: 0 } })} />);
    for (const demo of ["Atlant", "vexa", "Kairo", "m1rage", "zeNN", "Дефолт B-сайт Inferno", "Тайминги"]) expect(markup).not.toContain(demo);
  });
});

describe("lineups dialog", () => {
  it("newScriptDialogHasTheBoardsFieldsAndCopy", () => {
    const markup = renderToStaticMarkup(<LineupsView page={pageOf({ form: "script" })} />);
    const t = text(markup);
    expect(t).toContain("Новый скрипт");
    for (const copy of ["Название *", "Тип", "Карта", "Сторона", "Каждый шаг — с новой строки", "Ссылка на видео", "Сохранить"]) expect(t).toContain(copy);
    expect(markup).toContain('role="dialog"');
    expect(checked(markup, "type", "script")).toBe(true);
    expect(markup).toMatch(/<input(?=[^>]*name="title")(?=[^>]*required)[^>]*>/);
    expect(markup.slice(markup.indexOf('role="dialog"'))).not.toContain('name="id"');
  });

  it("newLineupDialogStartsOnTheFilteredMap", () => {
    const filters: LineupFilter = { ...FILTERS, map: "nuke" };
    const markup = renderToStaticMarkup(<LineupsView page={pageOf({ form: "lineup", filters })} />);
    expect(text(markup)).toContain("Новая раскидка");
    expect(checked(markup, "map", "nuke")).toBe(true);
    expect(checked(markup, "type", "smoke")).toBe(true);
    // a new entry shows up in the unfiltered list: no filters travel with it
    expect(markup).not.toContain('name="return_map"');
  });

  it("editDialogCarriesTheEntryAndTheFilters", () => {
    const markup = renderToStaticMarkup(<LineupsView page={pageOf({ form: "edit", filters: { ...FILTERS, map: "mirage" } })} />);
    expect(text(markup)).toContain("Редактирование");
    expect(markup).toContain('name="id" value="11111111-1111-4111-8111-111111111111"');
    expect(markup).toContain('name="return_map" value="mirage"');
    expect(markup).toContain('value="Смок в окно"');
    expect(markup).toContain('value="https://example.com/v"');
  });

  it("aRefusedSaveShowsTheErrorInsideTheDialog", () => {
    const markup = renderToStaticMarkup(<LineupsView page={pageOf({ form: "lineup", error: "invalid" })} />);
    expect((markup.match(/role="alert"/g) ?? []).length).toBe(1);
    expect(text(markup)).toContain("Проверьте название и ссылку на видео");
  });

  it("anErrorWithoutADialogIsABanner", () => {
    const markup = renderToStaticMarkup(<LineupsView page={pageOf({ error: "not-found" })} />);
    expect(text(markup)).toContain("Запись больше недоступна.");
  });

  it("headerButtonsOpenTheDialogKeepingTheFilters", () => {
    const markup = renderToStaticMarkup(<HeaderActions filters={{ ...FILTERS, map: "inferno" }} />);
    expect(text(markup)).toContain("Раскидка");
    expect(text(markup)).toContain("Скрипт");
    expect(markup).toContain('href="/staff/lineups?map=inferno&amp;form=lineup"');
    expect(markup).toContain('href="/staff/lineups?map=inferno&amp;form=script"');
  });
});

describe("lineups notices", () => {
  it("sayWhatHappened", () => {
    const created = text(renderToStaticMarkup(<LineupsView page={pageOf({ notice: "created" })} />));
    expect(created).toContain("Раскидка добавлена — игроки увидят её в дневнике");
    const script = text(renderToStaticMarkup(<LineupsView page={pageOf({ notice: "created", entries: [entry({ type: "script" })] })} />));
    expect(script).toContain("Скрипт добавлен — игроки увидят его в дневнике");
    expect(text(renderToStaticMarkup(<LineupsView page={pageOf({ notice: "saved" })} />))).toContain("Изменения сохранены");
    expect(text(renderToStaticMarkup(<LineupsView page={pageOf({ notice: "deleted" })} />))).toContain("Запись удалена");
  });
});

describe("lineupsHref", () => {
  it("leavesDefaultsOut", () => {
    expect(lineupsHref(FILTERS)).toBe("/staff/lineups");
    expect(lineupsHref({ ...FILTERS, entry: "x" }, { entry: "x" })).toBe("/staff/lineups?entry=x");
    expect(lineupsHref({ ...FILTERS, type: "script" }, { type: "all" })).toBe("/staff/lineups");
  });
});
