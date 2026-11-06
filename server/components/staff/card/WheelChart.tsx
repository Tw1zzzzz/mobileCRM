"use client";

import { useState, type CSSProperties } from "react";
import type { WheelFigure } from "@/lib/staff/card-wheel";
import styles from "./card.module.css";

/** The radar's coordinate system. */
const VIEW = 340;

/**
 * The radar and the eight values of «Колесо баланса». Pointing at a sector or a row (or tapping, or focusing a row)
 * marks that value on the radar and in the list. The marker and the tip keep the last value while they fade out.
 */
export function WheelChart({ figure }: { figure: WheelFigure }) {
  const [active, setActive] = useState<number | null>(null);
  const [last, setLast] = useState<number | null>(null);
  const pick = (index: number): void => {
    setActive(index);
    setLast(index);
  };
  const sector = last === null ? null : figure.sectors[last] ?? null;
  const item = last === null ? null : figure.list[last] ?? null;
  return (
    <div className={styles.wheel} onPointerLeave={(e) => e.pointerType === "mouse" && setActive(null)}>
      <div className={styles.radar}>
        <svg viewBox={`0 0 ${VIEW} ${VIEW}`} width="100%" height="100%" role="img" aria-label={figure.aria}>
          <g fill="none" stroke="#26304a">
            {figure.rings.map((points) => (
              <polygon key={points} points={points} />
            ))}
            <path d={figure.spokes} />
          </g>
          <polygon points={figure.polygon} fill="rgba(63,208,255,0.2)" stroke="#3fd0ff" strokeWidth="2.5" strokeLinejoin="round" />
          {figure.sectors.map((s, i) => (
            <polygon
              key={s.points}
              points={s.points}
              fill="rgba(63,208,255,0.12)"
              className={active === i ? `${styles.wheelSector} ${styles.wheelSectorOn}` : styles.wheelSector}
              onPointerEnter={() => pick(i)}
              onClick={() => pick(i)}
            />
          ))}
          {sector !== null && (
            <g
              className={styles.wheelMark}
              data-on={active !== null}
              style={{ "--px": `${sector.x}px`, "--py": `${sector.y}px` } as CSSProperties}
              pointerEvents="none"
            >
              <circle r="6" fill="#070b17" stroke="#3fd0ff" strokeWidth="3" />
            </g>
          )}
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
          <span
            className={styles.wheelTip}
            data-on={active !== null}
            style={{ "--x": sector.x / VIEW, "--y": sector.y / VIEW } as CSSProperties}
            role="status"
          >
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
            onPointerEnter={() => pick(i)}
            onClick={() => pick(i)}
            onFocus={() => pick(i)}
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
