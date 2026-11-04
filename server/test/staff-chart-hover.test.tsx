import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { ChartHover } from "@/components/staff/ChartHover";
import { testsPoints } from "@/lib/staff/card-charts";
import { wheelData } from "@/lib/staff/card-wheel";
import { fraction, nearestPoint, stepPoint, type HoverPoint } from "@/lib/staff/chart-hover";
import { sleepEnergyPoints, trendPoints } from "@/lib/staff/overview-hover";

const point = (x: number): HoverPoint => ({ x, title: String(x), rows: [] });
const DAYS = ["2026-10-13", "2026-10-14", "2026-10-15"];
const TODAY = "2026-10-15";

describe("nearestPoint", () => {
  const points = [point(0), point(0.5), point(1)];

  it("picksTheClosestDay", () => {
    expect(nearestPoint(points, 0.1)).toBe(0);
    expect(nearestPoint(points, 0.4)).toBe(1);
    expect(nearestPoint(points, 0.9)).toBe(2);
  });

  it("outsideTheChartIsTheEdgeDay", () => {
    expect(nearestPoint(points, -0.2)).toBe(0);
    expect(nearestPoint(points, 1.3)).toBe(2);
  });

  it("noPointsIsMinusOne", () => {
    expect(nearestPoint([], 0.5)).toBe(-1);
  });
});

describe("stepPoint", () => {
  it("startsFromTheEdgeArrowPointsAwayFrom", () => {
    expect(stepPoint(null, 1, 3)).toBe(0);
    expect(stepPoint(null, -1, 3)).toBe(2);
  });

  it("staysInsideTheDays", () => {
    expect(stepPoint(2, 1, 3)).toBe(2);
    expect(stepPoint(0, -1, 3)).toBe(0);
    expect(stepPoint(1, 1, 3)).toBe(2);
  });

  it("noDaysIsNothing", () => {
    expect(stepPoint(null, 1, 0)).toBeNull();
  });
});

describe("hover points", () => {
  it("sleepAndEnergyGiveEachDayItsValues", () => {
    const points = sleepEnergyPoints(DAYS, [7.5, null, 8], [6, null, 5.5], TODAY);
    expect(points).toHaveLength(3);
    expect(points[0].title).toBe("Вт · 13 окт");
    expect(points[0].rows[0]).toMatchObject({ label: "Сон", value: "7ч 30м" });
    expect(points[0].rows[1]).toMatchObject({ label: "Энергия", value: "6,0" });
    expect(points[2].title).toBe("Чт · Сегодня");
  });

  it("aDayWithoutDataSaysSoAndHasNoMarker", () => {
    const [, gap] = sleepEnergyPoints(DAYS, [7.5, null, 8], [6, null, 5.5], TODAY);
    expect(gap.title).toBe("Ср · Вчера");
    expect(gap.rows.map((r) => r.value)).toEqual(["нет данных", "нет данных"]);
    expect(gap.rows.every((r) => r.y === undefined)).toBe(true);
  });

  it("markersStayInsideTheChart", () => {
    // More hours than the axis holds: the bar is cut at the top and so is its marker, the text keeps the real value.
    const [long] = sleepEnergyPoints(DAYS, [13, null, null], [10, null, null], TODAY);
    expect(long.rows[0].value).toBe("13ч 00м");
    expect(long.rows[0].y).toBeGreaterThanOrEqual(0);
    expect(long.rows[0].y).toBeLessThanOrEqual(1);
  });

  it("trendFormatsTheCardsWay", () => {
    const points = trendPoints(DAYS, [1.2, null, 0.9], TODAY, "K/D", (v) => v.toFixed(2), "#fff");
    expect(points.map((p) => p.rows[0].value)).toEqual(["1.20", "нет данных", "0.90"]);
    expect(points[0].x).toBeLessThan(points[2].x);
    expect(points[0].rows[0].y).toBeLessThan(points[2].rows[0].y as number); // the higher value is higher up
  });

  it("testsGiveScoreAndStateIndex", () => {
    const points = testsPoints(DAYS, { "2026-10-13": 80 }, { "2026-10-13": 64, "2026-10-15": 70 }, TODAY);
    expect(points[0].rows.map((r) => r.value)).toEqual(["80", "64"]);
    expect(points[2].rows.map((r) => r.value)).toEqual(["нет данных", "70"]);
    expect(points[2].rows[0].y).toBeUndefined();
    expect(points[0].rows[0].y).toBe(fraction(180 - 80 * 1.6, 220));
  });
});

describe("wheel sectors", () => {
  const values = { physical: 10, emotional: 5, intellectual: 0, spiritual: 5, professional: 5, social: 5, environment: 5, financial: 5 };
  const docs = { wellbeing: { balance: [{ id: "b", day: "2026-10-15T09:00:00Z", values }] } };
  const figure = wheelData(docs, TODAY, null, () => "#").figure;

  it("oneSectorPerValueInTheOrderOfTheList", () => {
    expect(figure?.sectors).toHaveLength(8);
    expect(figure?.sectors).toHaveLength(figure?.list.length ?? 0);
  });

  it("theMarkerSitsOnTheValuesVertex", () => {
    // The first spoke goes straight up from the centre (170, 170), radius 115 for 10 of 10.
    expect(figure?.sectors[0]).toMatchObject({ x: 170, y: 55, left: "50.0%" });
    // A zero is at the centre.
    expect(figure?.sectors[2]).toMatchObject({ x: 170, y: 170, top: "50.0%" });
  });
});

describe("ChartHover", () => {
  it("rendersTheChartWithAFocusableLayerAndNoTooltipYet", () => {
    const markup = renderToStaticMarkup(
      <ChartHover points={[point(0.5)]} label="Сон">
        <svg aria-label="график" />
      </ChartHover>,
    );
    expect(markup).toContain('aria-label="график"');
    expect(markup).toContain("Сон: наведите или нажмите на график");
    expect(markup).toContain('tabindex="0"');
    expect(markup).not.toContain('role="status"');
  });
});
