import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { CardFrame } from "@/components/staff/card/CardFrame";
import { IdentityRow } from "@/components/staff/card/IdentityRow";
import { ProfileTab } from "@/components/staff/card/ProfileTab";
import { archetypeOf, latestMindsetScores, type Archetype } from "@/lib/staff/archetype";
import { loadCard, type CardData } from "@/lib/staff/card";
import { faceitLink } from "@/lib/staff/card-format";
import { MINDSET_TRAITS, TRAIT_TEXTS, type MindsetScores, type MindsetTrait } from "@/lib/staff/mindset";
import { resetDb, testDeps } from "./helpers";
import { putDoc, seedCardTeam } from "./staff-card-fixture";

interface FixtureCase {
  name: string;
  scores: MindsetScores | null;
  expected: Archetype | null;
}

const fixture: {
  traits: { trait: MindsetTrait; title: string; shortTitle: string; summary: string }[];
  cases: FixtureCase[];
} = JSON.parse(
  readFileSync(fileURLToPath(new URL("./fixtures/staff-archetype.json", import.meta.url)), "utf8"),
);

function named(name: string): FixtureCase {
  const found = fixture.cases.find((c) => c.name === name);
  if (!found) throw new Error(`No fixture case ${name}`);
  return found;
}

function scores(...values: number[]): MindsetScores {
  return Object.fromEntries(MINDSET_TRAITS.map((trait, i) => [trait, values[i]])) as MindsetScores;
}

describe("archetype", () => {
  it("traitTextsAreTheOnesInCore", () => {
    expect(fixture.traits.map((t) => t.trait)).toEqual([...MINDSET_TRAITS]);
    for (const t of fixture.traits) {
      expect(TRAIT_TEXTS[t.trait], t.trait).toEqual({ title: t.title, shortTitle: t.shortTitle, summary: t.summary });
    }
  });

  it("fixtureCasesMatchTheRules", () => {
    expect(fixture.cases.length).toBeGreaterThanOrEqual(10);
    for (const c of fixture.cases) expect(archetypeOf(c.scores), c.name).toEqual(c.expected);
  });

  it("archetypeLeadingTrait", () => {
    expect(archetypeOf(scores(90, 60, 50, 55, 40, 45))).toEqual({
      title: "Хладнокровие",
      summary: "Спокойно принимает решения под давлением: клатчи, овертаймы, важные матчи.",
      tags: ["Хладнокр."],
    });
    // the leading trait is the highest one, wherever it stands in the list
    expect(archetypeOf(scores(40, 50, 60, 55, 45, 85))?.title).toBe("Дисциплина");
    expect(archetypeOf(scores(10, 20, 30, 40, 50, 60))?.title).toBe("Дисциплина");
  });

  it("archetypeTiesGoToTheEarlierTrait", () => {
    expect(archetypeOf(named("tieAtTopFirstTraitWins").scores)?.title).toBe("Хладнокровие");
    expect(archetypeOf(named("threeWayTieKeepsTraitOrder").scores)?.tags).toEqual(["Антитильт", "Команда"]);
    expect(archetypeOf(named("tieOfLastTwoTraits").scores)?.title).toBe("Лидерство");
  });

  it("archetypeBalancedWhenSpreadUnder12", () => {
    const eleven = archetypeOf(scores(80, 75, 70, 72, 69, 69));
    expect(eleven?.title).toBe("Ровный по всем чертам");
    expect(eleven?.summary).toBe("Ни одна черта заметно не выделяется — нет и явных провалов.");
    expect(archetypeOf(scores(82, 75, 70, 72, 70, 70))?.title).toBe("Хладнокровие");
    expect(archetypeOf(scores(60, 60, 60, 60, 60, 60))?.title).toBe("Ровный по всем чертам");
  });

  it("archetypeTagsAreTheAppsStrengths", () => {
    expect(archetypeOf(scores(40, 40, 70, 40, 40, 40))?.tags).toEqual(["Фокус"]); // exactly 70 is a strength
    // below 70 the app falls back to the top trait
    expect(archetypeOf(scores(40, 40, 69, 40, 40, 40))?.tags).toEqual(["Фокус"]);
    expect(archetypeOf(scores(60, 60, 60, 60, 60, 60))?.tags).toEqual(["Хладнокр."]);
    // at most two, strongest first, however many reach 70
    expect(archetypeOf(scores(95, 90, 85, 80, 20, 20))?.tags).toEqual(["Хладнокр.", "Антитильт"]);
    expect(archetypeOf(scores(85, 40, 75, 40, 72, 40))?.tags).toEqual(["Хладнокр.", "Фокус"]);
    expect(archetypeOf(scores(70, 85, 40, 40, 40, 99))?.tags).toEqual(["Дисциплина", "Антитильт"]);
  });

  it("archetypeMissingTraitCountsAsZero", () => {
    const partial: MindsetScores = { composure: 60, resilience: 60, focus: 60, teamwork: 60, leadership: 60 };
    expect(archetypeOf(partial)?.title).toBe("Хладнокровие");
  });

  it("archetypeNullWithoutRuns", () => {
    expect(archetypeOf(null)).toBeNull();
    expect(latestMindsetScores(undefined)).toBeNull();
    expect(latestMindsetScores({ mindset: [] })).toBeNull();
    expect(latestMindsetScores({})).toBeNull();
    expect(latestMindsetScores("oops")).toBeNull();
    expect(latestMindsetScores({ mindset: "oops" })).toBeNull();
  });
});

describe("latest run", () => {
  const old = scores(10, 10, 10, 10, 10, 90);
  const recent = scores(90, 10, 10, 10, 10, 10);

  it("latestRunIsTheGreatestDateNotTheLastEntry", () => {
    const survey = {
      mindset: [
        { id: "b", date: "2026-10-20T10:00:00Z", scores: recent },
        { id: "a", date: "2026-09-01T10:00:00Z", scores: old },
      ],
    };
    expect(latestMindsetScores(survey)).toEqual(recent);
  });

  it("unfinishedRunsAreSkipped", () => {
    const survey = {
      mindset: [
        { id: "a", date: "2026-09-01T10:00:00Z", scores: old },
        { id: "b", date: "2026-10-20T10:00:00Z", scores: { composure: 99 } },
        { id: "c", date: "2026-10-21T10:00:00Z", scores: {} },
      ],
    };
    expect(latestMindsetScores(survey)).toEqual(old);
    expect(latestMindsetScores({ mindset: [{ id: "c", date: "2026-10-21T10:00:00Z", scores: {} }] })).toBeNull();
  });

  it("sameInstantTakesTheLaterEntryAndBadDatesLoseToGoodOnes", () => {
    const same = {
      mindset: [
        { id: "a", date: "2026-10-20T10:00:00Z", scores: old },
        { id: "b", date: "2026-10-20T10:00:00Z", scores: recent },
      ],
    };
    expect(latestMindsetScores(same)).toEqual(recent);
    const broken = {
      mindset: [
        { id: "a", date: "2026-09-01T10:00:00Z", scores: old },
        { id: "b", date: "yesterday", scores: recent },
      ],
    };
    expect(latestMindsetScores(broken)).toEqual(old);
    // a run whose date cannot be read still counts when nothing else exists
    expect(latestMindsetScores({ mindset: [{ id: "b", date: null, scores: recent }] })).toEqual(recent);
  });

  it("scoresThatAreNotNumbersMakeTheRunUnfinished", () => {
    const bad = { ...recent, focus: "x" };
    expect(latestMindsetScores({ mindset: [{ id: "a", date: "2026-10-20T10:00:00Z", scores: bad }] })).toBeNull();
    const nan = { ...recent, focus: Number.NaN };
    expect(latestMindsetScores({ mindset: [{ id: "a", date: "2026-10-20T10:00:00Z", scores: nan }] })).toBeNull();
  });
});

describe("faceit link", () => {
  it("faceitLinkOnlyForFaceitHost", () => {
    const ok = "https://www.faceit.com/ru/players/Nova";
    expect(faceitLink(ok)).toBe(ok);
    for (const bad of [
      null,
      "",
      "javascript:alert(1)",
      "JAVASCRIPT:alert(1)",
      "data:text/html,<script>alert(1)</script>",
      "http://www.faceit.com/ru/players/Nova",
      "https://faceit.com/ru/players/Nova",
      "https://evil.example/https://www.faceit.com/",
      "https://www.faceit.com.evil.example/ru/players/Nova",
      "https://www.faceit.com@evil.example/",
      "https://www.faceit.com",
      " https://www.faceit.com/ru/players/Nova",
      "www.faceit.com/ru/players/Nova",
      "//www.faceit.com/ru/players/Nova",
    ]) {
      expect(faceitLink(bad), String(bad)).toBeNull();
    }
  });
});

// ---------------------------------------------------------------- markup

const NO_ARCHETYPE_PLAYER = {
  id: "p1",
  nickname: "vega",
  initials: "VE",
  faceitUrl: null as string | null,
  lastActivity: "сегодня, 15:00",
};

function identity(patch: Partial<typeof NO_ARCHETYPE_PLAYER> = {}): string {
  return renderToStaticMarkup(createElement(IdentityRow, { player: { ...NO_ARCHETYPE_PLAYER, ...patch } }));
}

function words(markup: string): string {
  return markup.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim();
}

describe("identity row", () => {
  it("identityRowHasNoDemoNames", () => {
    const markup = identity();
    const text = words(markup);
    expect(text).toContain("VE");
    expect(text).toContain("vega");
    expect(text).toContain("Последняя активность: сегодня, 15:00");
    for (const demo of ["vexa", "Atlant", "Coach", "Демо", "Entry", "AWPer", "IGL", "Support", "Lurker", "Rifler", "Роль в CS2"]) {
      expect(markup, demo).not.toContain(demo);
    }
    // no role pill next to the nickname: the questionnaire is not readable
    expect(markup).not.toContain("st-ok");
  });

  it("telegramAndSteamAreDisabledWithoutData", () => {
    const markup = identity({ faceitUrl: "https://www.faceit.com/ru/players/Nova" });
    for (const name of ["Telegram", "Steam"]) {
      const button = new RegExp(`<span[^>]*aria-disabled[^>]*>(?:(?!</span>).)*${name}</span>`).exec(markup)?.[0] ?? "";
      expect(button, name).toContain('aria-disabled="true"');
      expect(button, name).toContain('title="нет данных"');
      expect(button, name).not.toContain("href");
    }
  });

  it("faceitIsALinkOnlyForARealFaceitAddress", () => {
    const real = identity({ faceitUrl: "https://www.faceit.com/ru/players/Nova" });
    expect(real).toMatch(/<a [^>]*href="https:\/\/www\.faceit\.com\/ru\/players\/Nova"/);
    expect(real).toContain('rel="noopener noreferrer"');
    expect(real).toContain('target="_blank"');
    expect(real).toMatch(/>FACEIT<\/a>/);

    for (const bad of [null, "javascript:alert(1)", "https://evil.example/", 'https://www.faceit.com.evil.example/"onmouseover="x']) {
      const markup = identity({ faceitUrl: bad });
      expect(markup, String(bad)).not.toContain("<a ");
      expect(markup, String(bad)).not.toContain("href");
      expect(markup, String(bad)).not.toContain("<button");
      expect(markup, String(bad)).not.toContain("javascript:");
      const button = /<span[^>]*aria-disabled[^>]*>(?:(?!<\/span>).)*FACEIT<\/span>/.exec(markup)?.[0] ?? "";
      expect(button, String(bad)).toContain('aria-disabled="true"');
      expect(button, String(bad)).toContain('title="нет данных"');
    }
  });

  it("faceitAddressIsEscapedNeverRawHtml", () => {
    const hostile = 'https://www.faceit.com/ru/players/"><script>alert(1)</script>';
    const markup = identity({ faceitUrl: hostile });
    expect(markup).not.toContain("<script");
    const href = /href="([^"]*)"/.exec(markup)?.[1] ?? "";
    expect(href.startsWith("https://www.faceit.com/")).toBe(true);
    expect(href).not.toMatch(/[<>"]/);
  });

  it("nicknameIsEscaped", () => {
    const markup = identity({ nickname: "<img src=x onerror=alert(1)>", initials: "<I" });
    expect(markup).not.toContain("<img");
    expect(markup).not.toContain("<I<");
  });
});

describe("profile tab", () => {
  it("archetypeCardShowsTitleSummaryAndTags", () => {
    const markup = renderToStaticMarkup(
      createElement(ProfileTab, {
        archetype: { title: "Дисциплина", summary: "Тренируется регулярно и по плану.", tags: ["Дисциплина", "Фокус"] },
      }),
    );
    const text = words(markup);
    expect(text).toContain("Архетип");
    expect(text).toContain("Дисциплина");
    expect(text).toContain("Тренируется регулярно и по плану.");
    expect(markup.match(/<li[^>]*>(Дисциплина|Фокус)<\/li>/g)).toHaveLength(2); // the tags
    expect(markup.match(/<span[^>]*>Дисциплина<\/span>/g)).toHaveLength(1); // the title
    expect(markup).not.toContain("нет данных");
  });

  it("archetypeCardWithoutTagsHasNoTagRow", () => {
    const markup = renderToStaticMarkup(
      createElement(ProfileTab, { archetype: { title: "Ровный по всем чертам", summary: "x", tags: [] } }),
    );
    expect(markup).not.toContain("<ul");
  });

  it("noFinishedRunShowsNoDataInsideTheArchetypeCard", () => {
    const markup = renderToStaticMarkup(createElement(ProfileTab, { archetype: null }));
    const text = words(markup);
    expect(text).toContain("Архетип");
    expect(text).toContain("нет данных");
  });

  it("noRoleCardNoCommunicationNotesNoDeadUploadButtons", () => {
    for (const archetype of [null, { title: "t", summary: "s", tags: [] }]) {
      const markup = renderToStaticMarkup(createElement(ProfileTab, { archetype }));
      for (const absent of ["Роль в CS2", "Основная роль", "Коммуникативная линия", "Схема общения", "Roadmap", "Mindmap", "Загрузить", "<textarea", "<button"]) {
        expect(markup, absent).not.toContain(absent);
      }
    }
  });
});

// ---------------------------------------------------------------- through the card

const deps = testDeps();
const TZ = "Europe/Moscow";

beforeEach(async () => {
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

/** Answers whose four items for each trait score exactly `score` (one of 0, 25, 50, 75, 100). */
function answersFor(score: Record<MindsetTrait, number>): Record<string, number> {
  const answers: Record<string, number> = {};
  for (let i = 0; i < 24; i += 1) {
    const trait = MINDSET_TRAITS[i % 6];
    const reversed = Math.floor(i / 6) % 2 === 1;
    const answer = 1 + score[trait] / 25;
    answers[`q${i + 1}`] = reversed ? 6 - answer : answer;
  }
  return answers;
}

function card(data: CardData): Extract<CardData, { kind: "card" }> {
  if (data.kind !== "card") throw new Error("expected a card");
  return data;
}

describe("profile through the card", () => {
  const uniform = (value: number) => Object.fromEntries(MINDSET_TRAITS.map((t) => [t, value])) as Record<MindsetTrait, number>;

  it("profileTabShowsTheLatestFinishedRunOfThePlayer", async () => {
    const t = await seedCardTeam(deps);
    await putDoc(deps, t.ids.vega, "player-survey", {
      survey: { role: "awper", goals: ["x"] },
      mindset: [
        { id: "m1", date: "2026-10-26T08:00:00Z", answers: answersFor({ ...uniform(25), composure: 100, focus: 75 }) },
        { id: "m0", date: "2026-09-01T08:00:00Z", answers: answersFor({ ...uniform(25), drive: 100 }) },
      ],
    });
    const data = card(await loadCard(deps, t.ctx, { player: t.ids.vega, tab: "profile" }, TZ));
    expect(data.archetype).toEqual({
      title: "Хладнокровие",
      summary: "Спокойно принимает решения под давлением: клатчи, овертаймы, важные матчи.",
      tags: ["Хладнокр.", "Фокус"],
    });
    const text = words(renderToStaticMarkup(createElement(CardFrame, { card: data })));
    expect(text).toContain("Архетип");
    expect(text).toContain("Хладнокровие");
    // the questionnaire answer «Роль в CS2» stays unreadable
    expect(text).not.toContain("AWPer");
    expect(text).not.toContain("awper");
    expect(text).not.toContain("Роль в CS2");
  });

  it("playerWithoutARunHasNoDataInTheArchetypeCard", async () => {
    const t = await seedCardTeam(deps);
    const data = card(await loadCard(deps, t.ctx, { player: t.ids.vega, tab: "profile" }, TZ));
    expect(data.archetype).toBeNull();
    const markup = renderToStaticMarkup(createElement(CardFrame, { card: data }));
    expect(words(markup)).toMatch(/Архетип нет данных/);
    expect(markup).not.toContain("NaN");
  });

  it("identityRowOfTheCardCarriesTheFaceitAddressOfThatPlayerOnly", async () => {
    const t = await seedCardTeam(deps);
    await deps.sql`update users set faceit_url = 'https://www.faceit.com/ru/players/Vega' where id = ${t.ids.vega}`;
    await deps.sql`update users set faceit_url = 'javascript:alert(1)' where id = ${t.ids.nova}`;
    const vega = renderToStaticMarkup(createElement(CardFrame, { card: card(await loadCard(deps, t.ctx, { player: t.ids.vega }, TZ)) }));
    expect(vega).toContain('href="https://www.faceit.com/ru/players/Vega"');
    const nova = renderToStaticMarkup(createElement(CardFrame, { card: card(await loadCard(deps, t.ctx, { player: t.ids.nova }, TZ)) }));
    expect(nova).not.toContain("javascript:");
    expect(nova).not.toContain("faceit.com");
    const ghost = renderToStaticMarkup(createElement(CardFrame, { card: card(await loadCard(deps, t.ctx, { player: t.ids.ghost }, TZ)) }));
    expect(ghost).not.toContain("faceit.com");
  });
});
