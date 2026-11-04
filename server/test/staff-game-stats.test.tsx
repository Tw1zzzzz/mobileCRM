import { readFileSync } from "node:fs";
import { join } from "node:path";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { GameForm } from "@/components/staff/game-stats/GameForm";
import { checkDraft } from "@/components/staff/game-stats/check";
import { newDraft, withCell, type Draft, type RosterPlayer } from "@/components/staff/game-stats/draft";

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
    expect(markup).toMatch(/<span role="rowheader"[^>]*>vega<\/span>/);
    expect(markup).toMatch(/<span role="rowheader"[^>]*>nova<\/span>/);
    for (const title of ["Убийства", "Смерти", "Ассисты", "Средний урон за раунд", "First Kills", "First Deaths", "Ср. мультикиллы", "Выигранные клатчи", "Ср. время ослепления, с"]) {
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
    expect(t).toContain("Необязательные метрики: пусто 10 ячеек. Игру можно сохранить и дополнить позже.");
    // The live block: 13:9 is a win, 22 rounds, no draw.
    expect(t).toContain("1 : 0");
    expect(t).toContain("по картам · 1 карта (П : П)");
    expect(t).toContain("100% ");
    expect(t).toContain("13–9");
    expect(t).toContain("K/D команды 2.00");
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
    expect(body).toContain("Сводка за период");
  });

  it("actionsStartWithRequireStaffContext", () => {
    const source = readFileSync(join(__dirname, "..", "app", "staff", "actions.ts"), "utf8");
    for (const name of ["saveGameAction", "deleteGameAction"]) {
      const body = source.slice(source.indexOf(`export async function ${name}`));
      expect(/\bawait\s+([A-Za-z_$][\w$.]*)/.exec(body)?.[1]).toBe("requireStaffContext");
    }
  });
});
