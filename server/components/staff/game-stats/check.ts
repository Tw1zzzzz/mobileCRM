import { plural } from "@/lib/staff/overview-format";
import { CELLS, MAX_ROWS, readCell, readScore, visibleRows, type Draft, type DraftMap } from "./draft";

/**
 * «Перед сохранением»: what is still missing in the draft, in the words of the board. The same checks decide whether
 * «Сохранить игру» may send the game, so the server's own validation is only the second line of defence.
 */

export interface MapCheck {
  /** Without the «Карта N: » prefix. */
  issues: string[];
  complete: boolean;
}

export interface DraftCheck {
  /** Everything to fix, each with its prefix, in the order of the form. */
  issues: string[];
  maps: MapCheck[];
  /** Empty optional cells over all the maps. */
  optionalEmpty: number;
}

const blank = (value: string): boolean => value.trim() === "";

function isCalendarDay(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [y, m, d] = value.split("-").map(Number);
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d;
}

function scoreIssue(map: DraftMap): string | null {
  if (blank(map.our) || blank(map.their)) return "укажите счёт";
  return readScore(map.our) === null || readScore(map.their) === null ? "счёт — целые числа от 0 до 30" : null;
}

function checkMap(draft: Draft, map: DraftMap): MapCheck {
  const rows = visibleRows(draft, map);
  const issues: string[] = [];
  if (map.map === "") issues.push("выберите карту");
  const score = scoreIssue(map);
  if (score) issues.push(score);
  if (map.pistol1 === "" || map.pistol2 === "") issues.push("отметьте оба пистолетных раунда");
  if (rows.length === 0) issues.push("добавьте хотя бы одного игрока");
  if (rows.length > MAX_ROWS) issues.push(`не больше ${MAX_ROWS} игроков на карте`);
  if (rows.some((row) => row.playerId === null && blank(row.nickname))) issues.push("укажите ник запасного");
  let missing = 0;
  let wrong = 0;
  for (const row of rows) {
    for (const spec of CELLS) {
      const cell = readCell(spec.key, row.cells[spec.key]);
      if (cell.kind === "empty" && spec.required) missing += 1;
      if (cell.kind === "bad") wrong += 1;
    }
  }
  if (missing > 0) {
    issues.push(`не заполнено ${missing} ${plural(missing, "обязательная ячейка", "обязательные ячейки", "обязательных ячеек")}`);
  }
  if (wrong > 0) issues.push(`${wrong} ${plural(wrong, "ячейка", "ячейки", "ячеек")} с недопустимым значением`);
  return { issues, complete: issues.length === 0 };
}

export function checkDraft(draft: Draft): DraftCheck {
  const maps = draft.maps.map((map) => checkMap(draft, map));
  const top = isCalendarDay(draft.playedOn) ? [] : ["Укажите дату игры"];
  const optionalEmpty = draft.maps.reduce(
    (total, map) =>
      total +
      visibleRows(draft, map).reduce(
        (sum, row) => sum + CELLS.filter((spec) => !spec.required && readCell(spec.key, row.cells[spec.key]).kind === "empty").length,
        0,
      ),
    0,
  );
  const issues = [...top, ...maps.flatMap((map, index) => map.issues.map((issue) => `Карта ${index + 1}: ${issue}`))];
  return { issues, maps, optionalEmpty };
}

/** The note under the checklist when optional cells are empty; null when none are. */
export function optionalNote(empty: number): string | null {
  if (empty === 0) return null;
  return `Необязательные метрики: пусто ${empty} ${plural(empty, "ячейка", "ячейки", "ячеек")}. Игру можно сохранить и дополнить позже.`;
}
