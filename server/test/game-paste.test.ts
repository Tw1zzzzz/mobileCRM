import { describe, expect, it } from "vitest";
import { parsePaste, pasteCells } from "@/components/staff/game-stats/paste";

describe("spreadsheet paste", () => {
  it("pasteParsesMessyRows", () => {
    // Windows line ends, a header line, blank lines, a gap in the middle of a row and a cell that is not a number.
    const text = "K\tD\tA\tADR\r\n\r\n24\t15\t5\t91,5\r\n\r\n18\t\t7\t76.25\r\n   \r\n9\tx\t1\t55";
    expect(parsePaste(text)).toEqual([
      ["24", "15", "5", "91.5"],
      ["18", "", "7", "76.25"],
      ["9", "", "1", "55"],
    ]);
  });

  it("readsSemicolonAndCommaSeparatedBlocks", () => {
    expect(parsePaste("24;15;5;91,5\n18;17;7;76,2")).toEqual([["24", "15", "5", "91.5"], ["18", "17", "7", "76.2"]]);
    expect(parsePaste("24,15,5,91.5\n18,17,7,76.2")).toEqual([["24", "15", "5", "91.5"], ["18", "17", "7", "76.2"]]);
  });

  it("decimalComma", () => {
    expect(parsePaste("91,5")).toEqual([["91.5"]]);
    expect(parsePaste("1,25\t0,4")).toEqual([["1.25", "0.4"]]);
    expect(parsePaste("1 ")).toEqual([["1"]]);
  });

  it("nonNumbersLeaveTheCellEmpty", () => {
    expect(parsePaste("abc\t-3\t1e3\t12%%\t.5\t7,\t8")).toEqual([["", "", "", "", "", "", "8"]]);
    // A line with no number at all is a header or a note, not data.
    expect(parsePaste("Игрок\tK\tD\nvega\t24\t15")).toEqual([["", "24", "15"]]);
    expect(parsePaste("K\tD\tA\tADR")).toEqual([]);
  });

  it("aColumnOfDecimalCommasIsOneColumn", () => {
    // A Russian-locale sheet copies one column as lines of «91,5»: no cell separator, not two columns.
    expect(parsePaste("91,5\n76,2")).toEqual([["91.5"], ["76.2"]]);
    expect(parsePaste("91,5\r\n\r\n76,25\r\n55,0")).toEqual([["91.5"], ["76.25"], ["55.0"]]);
    expect(parsePaste("78,5%\n80%")).toEqual([["78.5"], ["80"]]);
    // A CSV block (a line that is not a lone decimal) still splits on commas.
    expect(parsePaste("24,15,5\n18,17,7")).toEqual([["24", "15", "5"], ["18", "17", "7"]]);
    expect(parsePaste("91,5\n24,15,5")).toEqual([["91", "5"], ["24", "15", "5"]]);
  });

  it("aTrailingPercentSignIsDropped", () => {
    expect(parsePaste("78%\t80,5 %\t12%%")).toEqual([["78", "80.5", ""]]);
  });

  it("anEmptyClipboardIsNothing", () => {
    expect(parsePaste("")).toEqual([]);
    expect(parsePaste("\n \n")).toEqual([]);
  });

  it("pasteFromMiddleCell", () => {
    const grid = [["1", "2", "3"], ["4", "5", "6"]];
    // Table of 4 rows x 5 columns, paste into row 1, column 2: fills right and down.
    expect(pasteCells(grid, { row: 1, col: 2 }, { rows: 4, cols: 5 })).toEqual([
      { row: 1, col: 2, value: "1" }, { row: 1, col: 3, value: "2" }, { row: 1, col: 4, value: "3" },
      { row: 2, col: 2, value: "4" }, { row: 2, col: 3, value: "5" }, { row: 2, col: 4, value: "6" },
    ]);
  });

  it("dropsWhatDoesNotFitTheTable", () => {
    const grid = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"]];
    // Two players, two columns left of the cursor: the extra rows and columns are ignored.
    expect(pasteCells(grid, { row: 1, col: 3 }, { rows: 2, cols: 5 })).toEqual([
      { row: 1, col: 3, value: "1" }, { row: 1, col: 4, value: "2" },
    ]);
    expect(pasteCells(grid, { row: 5, col: 0 }, { rows: 2, cols: 5 })).toEqual([]);
  });
});
