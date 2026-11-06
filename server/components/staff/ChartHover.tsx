"use client";

import { useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent, type ReactNode } from "react";
import { nearestPoint, stepPoint, type HoverPoint } from "@/lib/staff/chart-hover";
import styles from "./chart-hover.module.css";

interface ChartHoverProps {
  points: readonly HoverPoint[];
  /** What the chart is, for the focusable layer: «Сон и энергия». */
  label: string;
  /** The chart itself (an SVG that fills the width). */
  children: ReactNode;
}

export interface HoverState {
  index: number;
  on: boolean;
  /** Counts the appearances: a new one starts at its day instead of gliding there from the last one. */
  entry: number;
}

/** The next state for a picked day, or `null` for none: the last day stays while the layer fades out. */
export function nextHover(prev: HoverState | null, index: number | null): HoverState | null {
  if (index === null) return prev === null || !prev.on ? prev : { ...prev, on: false };
  if (prev === null) return { index, on: true, entry: 1 };
  if (!prev.on) return { index, on: true, entry: prev.entry + 1 };
  return prev.index === index ? prev : { ...prev, index };
}

/** Which way the tooltip leans so it stays inside the chart near the edges. */
function edge(x: number): "start" | "end" | "middle" {
  return x < 0.2 ? "start" : x > 0.8 ? "end" : "middle";
}

/**
 * A layer over a chart: the mouse, a finger or ←/→ picks the nearest day, which gets a guide line, a marker per
 * value and a tooltip with the day's numbers. Leaving the chart (or Esc, or focus moving away) clears it.
 */
export function ChartHover({ points, label, children }: ChartHoverProps) {
  const [hover, setHover] = useState<HoverState | null>(null);
  const layer = useRef<HTMLDivElement>(null);
  const active = hover !== null && hover.on ? hover.index : null;

  const show = (index: number | null): void => setHover((prev) => nextHover(prev, index));

  const pick = (event: PointerEvent<HTMLDivElement>): void => {
    const box = layer.current?.getBoundingClientRect();
    if (box === undefined || box.width <= 0) return;
    const index = nearestPoint(points, (event.clientX - box.left) / box.width);
    show(index < 0 ? null : index);
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
    show(move());
  };

  const point = hover === null ? null : points[hover.index] ?? null;
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
          if (event.pointerType === "mouse") show(null);
        }}
        onKeyDown={onKeyDown}
        onBlur={() => show(null)}
      >
        {hover !== null && point !== null && (
          <div key={hover.entry} className={styles.overlay} data-on={hover.on} style={{ "--x": point.x } as CSSProperties}>
            <span className={styles.guide} aria-hidden="true" />
            {point.rows.map((row) =>
              row.y === undefined ? null : (
                <span
                  key={row.label}
                  className={styles.marker}
                  style={{ "--y": row.y, borderColor: row.color } as CSSProperties}
                  aria-hidden="true"
                />
              ),
            )}
            <div className={styles.tip} data-edge={edge(point.x)} role="status">
              <span className={styles.tipTitle}>{point.title}</span>
              {point.rows.map((row) => (
                <span key={row.label} className={styles.tipRow}>
                  {row.color !== undefined && <span className={styles.swatch} style={{ background: row.color }} />}
                  <span className={styles.tipLabel}>{row.label}</span>
                  <b className={styles.tipValue}>{row.value}</b>
                </span>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
