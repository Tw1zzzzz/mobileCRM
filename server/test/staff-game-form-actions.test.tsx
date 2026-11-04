import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { GameForm, SAVE_FAILED, sendGame } from "@/components/staff/game-stats/GameForm";
import { newDraft, type RosterPlayer } from "@/components/staff/game-stats/draft";
import { GAME_ERRORS } from "@/lib/staff/game-forms";

const ALPHA: RosterPlayer = { id: "a1111111-1111-4111-8111-111111111111", nickname: "vega" };
const BRAVO: RosterPlayer = { id: "b2222222-2222-4222-8222-222222222222", nickname: "nova" };

const render = (roster: readonly RosterPlayer[], initial = null as ReturnType<typeof newDraft> | null): string =>
  renderToStaticMarkup(<GameForm teamId="team-1" roster={roster} today="2026-11-04" initial={initial} />);

const text = (markup: string): string => markup.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ");

describe("sending the game", () => {
  it("aLostAnswerIsTheFailedSaveTextNotAnException", async () => {
    expect(SAVE_FAILED).toBe(GAME_ERRORS.failed);
    const seen: unknown[] = [];
    const crashed = async (game: unknown) => {
      seen.push(game);
      throw new TypeError("fetch failed");
    };
    expect(await sendGame(crashed, { id: "x" })).toEqual({ status: "error", message: GAME_ERRORS.failed });
    expect(seen).toEqual([{ id: "x" }]);
  });

  it("anAnswerPassesThrough", async () => {
    expect(await sendGame(async () => ({ status: "done", message: "Игра сохранена" }), {})).toEqual({ status: "done", message: "Игра сохранена" });
    expect(await sendGame(async () => ({ status: "error", message: GAME_ERRORS.invalid }), {})).toEqual({ status: "error", message: GAME_ERRORS.invalid });
  });
});

describe("«Не играл» in the markup", () => {
  it("everyRosterRowHasTheButtonAndStandInsDoNot", () => {
    const markup = render([ALPHA, BRAVO]);
    expect(markup).toContain('aria-label="vega: не играл"');
    expect(markup).toContain('aria-label="nova: не играл"');
    expect(markup).not.toContain("Вернуть");
    const single = renderToStaticMarkup(
      <GameForm teamId="t" roster={[ALPHA, BRAVO]} today="2026-11-04" initial={{ ...newDraft([ALPHA, BRAVO], "2026-11-04"), who: ALPHA.id }} />,
    );
    expect(single).not.toContain("не играл");
  });

  it("anAbsentPlayerCanBeRestored", () => {
    const draft = newDraft([ALPHA, BRAVO], "2026-11-04");
    const left = { ...draft, maps: [{ ...draft.maps[0], rows: draft.maps[0].rows.map((r) => (r.playerId === BRAVO.id ? { ...r, absent: true } : r)) }] };
    const markup = render([ALPHA, BRAVO], left);
    expect(text(markup)).toContain("Не играл: nova · Вернуть");
    expect(markup).not.toContain('aria-label="nova, Убийства"');
    expect(text(markup)).toContain("Карта 1: не заполнено 4 обязательные ячейки");
  });
});

