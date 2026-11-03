import type { LineupError, LineupFilter, LineupFormMode, LineupMap, LineupSide, StaffLineupType } from "@/lib/staff/lineups";

/** Names, colours and icons of the lineups board (`Lineups.dc.html`). */

export const MAP_NAMES: Record<LineupMap, string> = {
  mirage: "Mirage", inferno: "Inferno", nuke: "Nuke", ancient: "Ancient", anubis: "Anubis", dust2: "Dust2", train: "Train",
};

/** RGB triplet of each map's tile tint. */
const MAP_TINT: Record<LineupMap, string> = {
  mirage: "232,170,80", inferno: "214,96,64", nuke: "64,190,190", ancient: "90,170,110",
  anubis: "110,140,230", dust2: "210,180,110", train: "150,160,180",
};

export const TYPE_NAMES: Record<StaffLineupType, string> = {
  smoke: "Смок", flash: "Флешка", molotov: "Молотов", grenade: "HE", script: "Скрипт", note: "Заметка",
};

/** Type icon path (24x24 stroke) and its stroke colour. */
export const TYPE_ICONS: Record<StaffLineupType, { path: string; color: string }> = {
  smoke: { path: "M7 18h10a4 4 0 0 0 .5-8 5.5 5.5 0 0 0-10.6-1.2A4.5 4.5 0 0 0 7 18z", color: "#D5DCEC" },
  flash: { path: "M12 2v4M12 18v4M2 12h4M18 12h4M5 5l3 3M16 16l3 3M5 19l3-3M16 8l3-3M12 9a3 3 0 1 0 0 6a3 3 0 0 0 0-6z", color: "#FFF0A0" },
  molotov: { path: "M12 2c1 4 6 6 6 12a6 6 0 0 1-12 0c0-3 1.5-5 3-6 0 2 1 3 2 3 0-3-1-5 1-9z", color: "#FF9A5A" },
  grenade: { path: "M12 8a6 6 0 1 0 0 12a6 6 0 0 0 0-12zM10 8V5h4v3M14 5l3-2", color: "#7FE0FF" },
  script: { path: "M9 6h11M9 12h11M9 18h11M4 6h.01M4 12h.01M4 18h.01", color: "#C9A6FF" },
  note: { path: "M4 20h4L19 9l-4-4L4 16v4zM13 7l4 4", color: "#E3A8FF" },
};

export const SIDE_NAMES: Record<LineupSide, string> = { t: "Атака (T)", ct: "Защита (CT)" };

/** Background of a tile: the map's tint fading into the panel colour. */
export function tileBackground(map: LineupMap): string {
  return `linear-gradient(135deg, rgba(${MAP_TINT[map]},0.42), rgba(16,23,43,0.95) 75%)`;
}

export const PATH = "/staff/lineups";

/** The page's URL with these filters, plus an optional selected entry and dialog. Defaults stay out of the URL. */
export function lineupsHref(
  filters: LineupFilter,
  change: Partial<LineupFilter> & { form?: LineupFormMode | null } = {},
): string {
  const next = { ...filters, entry: null, ...change };
  const query = new URLSearchParams();
  if (next.q) query.set("q", next.q);
  if (next.map !== "all") query.set("map", next.map);
  if (next.type !== "all") query.set("type", next.type);
  if (next.side !== "all") query.set("side", next.side);
  if (next.favorites) query.set("fav", "1");
  if (next.entry) query.set("entry", next.entry);
  if (next.form) query.set("form", next.form);
  const value = query.toString();
  return `${PATH}${value ? `?${value}` : ""}`;
}

export const ERROR_TEXT: Record<LineupError, string> = {
  invalid: "Проверьте название и ссылку на видео: она должна начинаться с https://",
  "not-found": "Запись больше недоступна.",
  "save-failed": "Не удалось сохранить запись. Обновите страницу и попробуйте снова.",
};
