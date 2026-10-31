import Link from "next/link";
import type { WheelData } from "@/lib/staff/card-wheel";
import styles from "./card.module.css";

/** «Колесо баланса»: the last seven days as chips, the radar of the chosen day and its eight values. */
export function WheelTab({ wheel }: { wheel: WheelData }) {
  const { figure } = wheel;
  return (
    <div className={styles.body}>
      <div role="group" aria-label="День" className={styles.chips}>
        {wheel.chips.map((chip) => (
          <Link
            key={chip.href}
            href={chip.href}
            scroll={false}
            className={chip.on ? `${styles.chip} ${styles.chipOn}` : styles.chip}
            aria-current={chip.on ? "date" : undefined}
          >
            {chip.label}
          </Link>
        ))}
      </div>
      {figure === null ? (
        <div className={styles.drop}>Игрок не заполнил колесо баланса в этот день</div>
      ) : (
        <div className={styles.wheel}>
          <div className={styles.radar}>
            <svg viewBox="0 0 340 340" width="100%" height="100%" role="img" aria-label={figure.aria}>
              <g fill="none" stroke="#26304a">
                {figure.rings.map((points) => (
                  <polygon key={points} points={points} />
                ))}
                <path d={figure.spokes} />
              </g>
              <polygon
                points={figure.polygon}
                fill="rgba(63,208,255,0.2)"
                stroke="#3fd0ff"
                strokeWidth="2.5"
                strokeLinejoin="round"
              />
            </svg>
            {figure.labels.map((label) => (
              <span key={label.text} className={styles.rl} style={{ left: label.left, top: label.top }}>
                {label.text}
              </span>
            ))}
          </div>
          <div className={styles.wheelList}>
            {figure.list.map((item) => (
              <div key={item.name} className={styles.kv}>
                <span className={styles.kvName}>{item.name}</span>
                <span className={styles.heavy}>{item.value}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
