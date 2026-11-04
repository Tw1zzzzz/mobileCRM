import { GAME_STATS_PATH } from "@/lib/staff/game-summary";

/**
 * Where the site goes back to after a game is deleted: the page URL the form carried (so the summary filters stay),
 * but only a path on the «Игровая статистика» page itself; anything else, however it was written, is the bare page.
 */
export function gameStatsReturnPath(raw: string | undefined): string {
  if (raw === undefined || !raw.startsWith(GAME_STATS_PATH)) return GAME_STATS_PATH;
  const rest = raw.slice(GAME_STATS_PATH.length);
  const onThePage = rest === "" || rest.startsWith("?") || rest.startsWith("#");
  // No control characters or backslashes: the value ends up in a Location header.
  // eslint-disable-next-line no-control-regex
  if (!onThePage || /[\u0000- \\]/.test(raw)) return GAME_STATS_PATH;
  return raw;
}
