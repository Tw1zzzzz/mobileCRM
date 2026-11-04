/**
 * Pasting a block of cells from a spreadsheet (Excel, Google Sheets, a CSV file) into the player table. Pure
 * functions: `parsePaste` turns the clipboard text into rows of cells, `pasteCells` says which table cell gets which
 * value when the block is pasted into one of them.
 */

export interface CellPosition {
  row: number;
  col: number;
}

export interface PasteCell extends CellPosition {
  value: string;
}

const NUMBER = /^\d+(?:[.,]\d+)?$/;
/** A line that is one number, with at most one comma between its digits (and an optional %): a decimal comma. */
const DECIMAL_COMMA = /^\s*\d+(?:,\d+)?%?\s*$/;

/** The text of one cell as a number with a dot (one trailing % dropped), or "" when it is not a plain non-negative number. */
function cleanCell(raw: string): string {
  const text = raw.trim().replace(/%$/, "").trim();
  return NUMBER.test(text) ? text.replace(",", ".") : "";
}

/**
 * What separates the cells: tabs, else semicolons, else commas. When every line is one number
 * with at most one decimal comma (`91,5` alone, or a column of them, as a Russian-locale sheet copies it) the block has
 * no separator: one column.
 */
function delimiterOf(lines: readonly string[]): string | null {
  const text = lines.join("\n");
  if (text.includes("\t")) return "\t";
  if (text.includes(";")) return ";";
  if (lines.every((line) => DECIMAL_COMMA.test(line))) return null;
  return text.includes(",") ? "," : null;
}

/**
 * Rows of cells of the pasted text. Cells are split by tabs, else by semicolons, else by commas; a decimal comma is
 * accepted (`91,5` → `91.5`), as is one trailing `%` (`78%` → `78`); in a line with numbers anything else that is not a
 * number (a stray word) becomes an empty cell. Blank lines and lines without a single number (the header `K D A ADR` copied along with the data)
 * carry nothing and are dropped, so they do not push the data a row down. Rows may differ in length.
 */
export function parsePaste(text: string): string[][] {
  const lines = text.split(/\r\n|\r|\n/).filter((line) => line.trim() !== "");
  const delimiter = delimiterOf(lines);
  const rows = lines.map((line) => (delimiter === null ? [line] : line.split(delimiter)).map(cleanCell));
  return rows.filter((cells) => cells.some((cell) => cell !== ""));
}

/**
 * The cells a pasted block fills: it starts at `from` and runs right and down; what falls outside a table of
 * `size` rows and columns (more rows than players, more columns than the table has) is dropped.
 */
export function pasteCells(grid: readonly (readonly string[])[], from: CellPosition, size: { rows: number; cols: number }): PasteCell[] {
  return grid.flatMap((cells, r) =>
    cells.flatMap((value, c) => {
      const row = from.row + r;
      const col = from.col + c;
      return row < size.rows && col < size.cols ? [{ row, col, value }] : [];
    }),
  );
}
