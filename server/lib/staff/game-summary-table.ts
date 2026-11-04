import type { GameKind, GameSummary } from "@/lib/staff/games";
import { periodTotals, type StatTotals } from "@/lib/staff/games-math";
import { NO_DATA, plural } from "@/lib/staff/overview-format";
import { TEAM } from "@/lib/staff/overview";

/**
 * The numbers of «Сводка за период»: four tiles and the table of the board (rows are the metrics, the first column
 * is the whole period and every game is a column after it, newest first). Everything is computed by
 * `games-math.ts` from the games the page loaded; a value that cannot be computed is «—» in a cell and
 * «нет данных» in a tile, never a made-up zero.
 */

export const KIND_LABELS: Record<GameKind, string> = { pracc: "Пракк", tournament: "Турнир" };

const DASH = "—";

const comma = (text: string): string => text.replace(".", ",");
const whole = (v: number | null): string => (v === null ? DASH : String(Math.round(v)));
const oneDecimal = (v: number | null): string => (v === null ? DASH : comma(String(Math.round(v * 10) / 10)));
const twoDecimals = (v: number | null): string => (v === null ? DASH : comma(v.toFixed(2)));
const percent = (v: number | null): string => (v === null ? DASH : `${Math.round(v * 100)}%`);
const signed = (v: number | null): string => (v === null ? DASH : `${v > 0 ? "+" : ""}${v}`);
const ofTotal = (part: number, all: number): string => (all > 0 ? `${part} / ${all}` : DASH);

interface MetricRow {
  label: string;
  format: (t: StatTotals) => string;
}

const GROUPS: readonly { title: string; rows: readonly MetricRow[] }[] = [
  {
    title: "K/D",
    rows: [
      { label: "Убийства (Kills)", format: (t) => (t.lines > 0 ? String(t.kills) : DASH) },
      { label: "Смерти (Deaths)", format: (t) => (t.lines > 0 ? String(t.deaths) : DASH) },
      { label: "Ассисты (Assists)", format: (t) => (t.lines > 0 ? String(t.assists) : DASH) },
      { label: "K/D Ratio", format: (t) => twoDecimals(t.kd) },
    ],
  },
  {
    title: "Аналитика",
    rows: [
      { label: "ADR", format: (t) => oneDecimal(t.adr) },
      { label: "KPR", format: (t) => twoDecimals(t.kpr) },
      { label: "Смерти / раунд", format: (t) => twoDecimals(t.dpr) },
      { label: "KAST", format: (t) => (t.kast === null ? DASH : `${Math.round(t.kast)}%`) },
      { label: "First Kills", format: (t) => whole(t.fk) },
      { label: "First Deaths", format: (t) => whole(t.fd) },
      { label: "Разница опен-дуэлей", format: (t) => signed(t.openingDiff) },
      { label: "UDR", format: (t) => oneDecimal(t.udr) },
      { label: "Ср. мультикиллы", format: (t) => oneDecimal(t.multikills) },
      { label: "Выигранные клатчи", format: (t) => whole(t.clutches) },
      { label: "Ср. время ослепления, с", format: (t) => oneDecimal(t.flashTime) },
    ],
  },
  {
    title: "Карты и раунды",
    rows: [
      { label: "Карты (П–П–Н)", format: (t) => (t.maps > 0 ? `${t.mapsWon}–${t.mapsLost}–${t.mapsDrawn}` : DASH) },
      { label: "Win Rate", format: (t) => percent(t.winRate) },
      { label: "Раунды (выиграно / всего)", format: (t) => ofTotal(t.roundsWon, t.rounds) },
      { label: "Round Win Rate", format: (t) => percent(t.roundWinRate) },
      { label: "AVG Rounds Won", format: (t) => oneDecimal(t.avgRoundsWon) },
      { label: "AVG Rounds Lost", format: (t) => oneDecimal(t.avgRoundsLost) },
      { label: "Пистолетные (выиграно / всего)", format: (t) => ofTotal(t.pistolsWon, t.pistols) },
      { label: "Pistol Win Rate", format: (t) => percent(t.pistolWinRate) },
    ],
  },
];

export interface SummaryTile {
  label: string;
  value: string;
}

export interface SummaryColumn {
  /** The game this column is; null for the whole period. */
  gameId: string | null;
  title: string;
  sub: string;
  /** `dd.mm`, for the labels of the column's buttons. */
  dateLabel: string;
}

export type SummaryRow = { kind: "group"; label: string } | { kind: "data"; label: string; cells: string[] };

export interface Summary {
  tiles: SummaryTile[];
  columns: SummaryColumn[];
  rows: SummaryRow[];
}

const shortDate = (day: string): string => {
  const [, month, date] = day.split("-");
  return `${date}.${month}`;
};

const capitalized = (map: string): string => map.charAt(0).toUpperCase() + map.slice(1);

function columnOf(game: GameSummary, who: string): SummaryColumn {
  const played = who === TEAM ? game.maps : game.maps.filter((m) => m.rows.some((r) => r.playerId === who));
  const maps = played.map((m) => capitalized(m.map)).join(", ");
  const dateLabel = shortDate(game.playedOn);
  return {
    gameId: game.id,
    title: `${dateLabel} · ${KIND_LABELS[game.kind]}`,
    sub: `${game.opponent ? `vs ${game.opponent} · ` : ""}${maps}`,
    dateLabel,
  };
}

/** The tiles, columns and rows for the loaded games; with a player chosen (`who`), their lines and maps only. */
export function buildSummary(games: readonly GameSummary[], who: string): Summary {
  const total = periodTotals(games, who === TEAM ? undefined : who);
  const tile = (label: string, value: string): SummaryTile => ({ label, value: value === DASH ? NO_DATA : value });
  const tiles = [
    tile("Игр за период", String(total.games)),
    tile("K/D", twoDecimals(total.kd)),
    tile("ADR", oneDecimal(total.adr)),
    tile("Win Rate по картам", percent(total.winRate)),
  ];
  if (games.length === 0) return { tiles, columns: [], rows: [] };
  const columns: SummaryColumn[] = [
    { gameId: null, title: "За период", sub: `${games.length} ${plural(games.length, "игра", "игры", "игр")}`, dateLabel: "" },
    ...games.map((g) => columnOf(g, who)),
  ];
  const rows = GROUPS.flatMap((group): SummaryRow[] => [
    { kind: "group", label: group.title },
    ...group.rows.map((row): SummaryRow => ({
      kind: "data",
      label: row.label,
      cells: [row.format(total), ...games.map((g) => row.format(g.totals))],
    })),
  ]);
  return { tiles, columns, rows };
}
