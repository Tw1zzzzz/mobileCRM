import Link from "next/link";
import type { WheelData } from "@/lib/staff/card-wheel";
import { WheelChart } from "./WheelChart";
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
        <WheelChart figure={figure} />
      )}
    </div>
  );
}
