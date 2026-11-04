"use client";

import { useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode } from "react";
import { nearestPoint, stepPoint, type HoverPoint } from "@/lib/staff/chart-hover";
import styles from "./chart-hover.module.css";

interface ChartHoverProps {
  points: readonly HoverPoint[];
  /** What the chart is, for the focusable layer: «Сон и энергия». */
  label: string;
  /** The chart itself (an SVG that fills the width). */
  children: ReactNode;
}

const percent = (value: number): string => `${(value * 100).toFixed(2)}%`;

/** Which way the tooltip leans so it stays inside the chart near the edges. */
function edge(x: number): "start" | "end" | "middle" {
  return x < 0.2 ? "start" : x > 0.8 ? "end" : "middle";
}

/**
 * A layer over a chart: the mouse, a finger or ←/→ picks the nearest day, which gets a guide line, a marker per
 * value and a tooltip with the day's numbers. Leaving the chart (or Esc, or focus moving away) clears it.
 */
export function ChartHover({ points, label, children }: ChartHoverProps) {
  const [active, setActive] = useState<number | null>(null);
  const layer = useRef<HTMLDivElement>(null);

  const pick = (event: PointerEvent<HTMLDivElement>): void => {
    const box = layer.current?.getBoundingClientRect();
    if (box === undefined || box.width <= 0) return;
    const index = nearestPoint(points, (event.clientX - box.left) / box.width);
    setActive(index < 0 ? null : index);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    const moves: Record<string, () => number | null> = {
      ArrowRight: () => stepPoint(active, 1, points.length),
      ArrowLeft: () => stepPoint(active, -1, points.length),
      Home: () => (points.length > 0 ? 0 : null),
      End: () => (points.length > 0 ? points.length - 1 : null),
      Escape: () => null,
    };
    const move = moves[event.key];
    if (move === undefined) return;
    event.preventDefault();
    setActive(move());
  };

  const point = active === null ? null : points[active] ?? null;
  return (
    <div className={styles.wrap}>
      {children}
      <div
        ref={layer}
        className={styles.layer}
        tabIndex={points.length > 0 ? 0 : -1}
        aria-label={`${label}: наведите или нажмите на график, стрелки — по дням`}
        onPointerMove={pick}
        onPointerDown={pick}
        onPointerLeave={(event) => {
          if (event.pointerType === "mouse") setActive(null);
        }}
        onKeyDown={onKeyDown}
        onBlur={() => setActive(null)}
      >
        {point !== null && (
          <>
            <span className={styles.guide} style={{ left: percent(point.x) }} aria-hidden="true" />
            {point.rows.map((row) =>
              row.y === undefined ? null : (
                <span
                  key={row.label}
                  className={styles.marker}
                  style={{ left: percent(point.x), top: percent(row.y), borderColor: row.color }}
                  aria-hidden="true"
                />
              ),
            )}
            <div className={styles.tip} data-edge={edge(point.x)} style={{ left: percent(point.x) }} role="status">
              <span className={styles.tipTitle}>{point.title}</span>
              {point.rows.map((row) => (
                <span key={row.label} className={styles.tipRow}>
                  {row.color !== undefined && <span className={styles.swatch} style={{ background: row.color }} />}
                  <span className={styles.tipLabel}>{row.label}</span>
                  <b className={styles.tipValue}>{row.value}</b>
                </span>
              ))}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
