"use client";

import { useState } from "react";
import type { WheelFigure } from "@/lib/staff/card-wheel";
import styles from "./card.module.css";

/**
 * The radar and the eight values of «Колесо баланса». Pointing at a sector or a row (or tapping, or focusing a row)
 * marks that value on the radar and in the list.
 */
export function WheelChart({ figure }: { figure: WheelFigure }) {
  const [active, setActive] = useState<number | null>(null);
  const sector = active === null ? null : figure.sectors[active] ?? null;
  const item = active === null ? null : figure.list[active] ?? null;
  return (
    <div className={styles.wheel} onPointerLeave={(e) => e.pointerType === "mouse" && setActive(null)}>
      <div className={styles.radar}>
        <svg viewBox="0 0 340 340" width="100%" height="100%" role="img" aria-label={figure.aria}>
          <g fill="none" stroke="#26304a">
            {figure.rings.map((points) => (
              <polygon key={points} points={points} />
            ))}
            <path d={figure.spokes} />
          </g>
          <polygon points={figure.polygon} fill="rgba(63,208,255,0.2)" stroke="#3fd0ff" strokeWidth="2.5" strokeLinejoin="round" />
          {sector !== null && (
            <>
              <polygon points={sector.points} fill="rgba(63,208,255,0.12)" pointerEvents="none" />
              <circle cx={sector.x} cy={sector.y} r="6" fill="#070b17" stroke="#3fd0ff" strokeWidth="3" pointerEvents="none" />
            </>
          )}
          {figure.sectors.map((s, i) => (
            <polygon
              key={s.points}
              points={s.points}
              fill="transparent"
              className={styles.wheelSector}
              onPointerEnter={() => setActive(i)}
              onClick={() => setActive(i)}
            />
          ))}
        </svg>
        {figure.labels.map((label, i) => (
          <span
            key={label.text}
            className={active === i ? `${styles.rl} ${styles.rlOn}` : styles.rl}
            style={{ left: label.left, top: label.top }}
          >
            {label.text}
          </span>
        ))}
        {sector !== null && item !== null && (
          <span className={styles.wheelTip} style={{ left: sector.left, top: sector.top }} role="status">
            {item.value}
          </span>
        )}
      </div>
      <div className={styles.wheelList}>
        {figure.list.map((row, i) => (
          <div
            key={row.name}
            className={active === i ? `${styles.kv} ${styles.kvOn}` : styles.kv}
            tabIndex={0}
            onPointerEnter={() => setActive(i)}
            onClick={() => setActive(i)}
            onFocus={() => setActive(i)}
            onBlur={() => setActive(null)}
          >
            <span className={styles.kvName}>{row.name}</span>
            <span className={styles.heavy}>{row.value}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
