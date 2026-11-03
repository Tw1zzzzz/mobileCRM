import { describe, expect, it } from "vitest";
import { foundLabel, lineupWhen } from "../lib/staff/lineups-format";

const TZ = "Europe/Moscow";
const TODAY = "2026-10-26";

describe("lineupWhen", () => {
  it("wordsTodayAndYesterday", () => {
    expect(lineupWhen(new Date("2026-10-26T20:30:00Z"), TODAY, TZ)).toBe("сегодня"); // 23:30 Moscow, still the 26th
    expect(lineupWhen(new Date("2026-10-26T21:30:00Z"), "2026-10-27", TZ)).toBe("сегодня"); // already the 27th
    expect(lineupWhen(new Date("2026-10-25T10:00:00Z"), TODAY, TZ)).toBe("вчера");
  });

  it("namesOlderDaysByDayAndMonth", () => {
    expect(lineupWhen(new Date("2026-10-03T10:00:00Z"), TODAY, TZ)).toBe("3 окт");
    expect(lineupWhen(new Date("2026-05-09T10:00:00Z"), TODAY, TZ)).toBe("9 мая");
  });

  it("addsTheYearOfAnEarlierOne", () => {
    expect(lineupWhen(new Date("2025-12-31T10:00:00Z"), TODAY, TZ)).toBe("31 дек 2025");
  });
});

describe("foundLabel", () => {
  it("pluralisesAndNamesTheMap", () => {
    expect(foundLabel(1, null)).toBe("1 запись");
    expect(foundLabel(3, null)).toBe("3 записи");
    expect(foundLabel(11, null)).toBe("11 записей");
    expect(foundLabel(21, "Mirage")).toBe("21 запись на Mirage");
    expect(foundLabel(0, "Nuke")).toBe("0 записей на Nuke");
    expect(foundLabel(500, null, true)).toBe("500+ записей");
  });
});
