"use client";

import { useState } from "react";
import type { MoodCategoryShare } from "@/lib/staff/metrics";
import type { DonutArc } from "@/lib/staff/overview-charts";
import { fmt1, records } from "@/lib/staff/overview-format";
import styles from "./overview.module.css";

const CATEGORY_COLORS = ["#3FD0FF", "#1F6BFF", "#7C8BB0", "#FF8A3D"] as const;

interface MoodBreakdownProps {
  categories: readonly MoodCategoryShare[];
  arcs: readonly DonutArc[];
  average: number;
  aria: string;
  /** «среднее по команде» or «среднее». */
  averageLabel: string;
}

/**
 * The donut and the category list of «Настроение». Pointing at an arc or a row (or tapping, or focusing a row)
 * brings that category forward: its arc thickens, the others fade, and the centre shows its share.
 */
export function MoodBreakdown({ categories, arcs, average, aria, averageLabel }: MoodBreakdownProps) {
  const [active, setActive] = useState<number | null>(null);
  const shown = active === null ? null : categories[active] ?? null;

  return (
    <div className={styles.moodBody} onPointerLeave={(e) => e.pointerType === "mouse" && setActive(null)}>
      <div className={styles.donut}>
        <svg width="200" height="200" viewBox="0 0 200 200" role="img" aria-label={aria}>
          <circle cx="100" cy="100" r="80" fill="none" stroke="#1E2740" strokeWidth="18" />
          <g transform="rotate(-90 100 100)" fill="none" strokeLinecap="round">
            {arcs.map((arc, i) => (
              <circle
                key={categories[i].name}
                cx="100"
                cy="100"
                r="80"
                stroke={CATEGORY_COLORS[i]}
                strokeWidth={active === i ? 24 : 18}
                strokeDasharray={arc.dasharray}
                strokeDashoffset={arc.dashoffset}
                strokeOpacity={arc.visible ? (active === null || active === i ? 1 : 0.3) : 0}
                pointerEvents={arc.visible ? "stroke" : "none"}
                className={styles.donutArc}
                onPointerEnter={() => setActive(i)}
                onClick={() => setActive(i)}
              />
            ))}
          </g>
        </svg>
        <div className={styles.donutCenter} aria-live="polite">
          <div key={shown?.name ?? "average"} className={styles.donutSwap}>
            {shown === null ? (
              <>
                <span className={styles.donutValue}>{fmt1(average)}</span>
                <span className={styles.donutLabel}>{averageLabel}</span>
              </>
            ) : (
              <>
                <span className={styles.donutValue}>{shown.percent}%</span>
                <span className={styles.donutLabel}>{shown.name}</span>
                <span className={styles.donutLabel}>{records(shown.count)}</span>
              </>
            )}
          </div>
        </div>
      </div>
      <div className={styles.cats}>
        <span className={styles.catsTitle}>По категориям</span>
        {categories.map((c, i) => (
          <div
            className={active === i ? `${styles.cat} ${styles.catOn}` : styles.cat}
            key={c.name}
            tabIndex={0}
            onPointerEnter={() => setActive(i)}
            onClick={() => setActive(i)}
            onFocus={() => setActive(i)}
            onBlur={() => setActive(null)}
          >
            <span className={styles.catDot} style={{ background: CATEGORY_COLORS[i] }} />
            <span className={styles.catName}>
              <span className={styles.catNameMain}>{c.name}</span>
              <span className={styles.catNote}>{c.range}</span>
            </span>
            <span className={styles.catNums}>
              <span className={styles.catPercent}>{c.percent}%</span>
              <span className={styles.catNote}>{records(c.count)}</span>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
