import type { KpiTile } from "@/lib/staff/card";
import styles from "./card.module.css";

const TONE = { up: styles.up, down: styles.down, flat: "" } as const;

/** The four tiles under the filters: mean score, state index and the two «high» counters. */
export function KpiStrip({ tiles }: { tiles: readonly KpiTile[] }) {
  return (
    <div className={styles.kpis}>
      {tiles.map((tile) => (
        <div key={tile.label} className={styles.kpi}>
          <span className={styles.kpiLabel}>{tile.label}</span>
          <span className={styles.kpiValue}>{tile.value}</span>
          {tile.note && (
            <span className={`${styles.kpiNote} ${TONE[tile.note.tone]}`} data-tone={tile.note.tone}>
              {tile.note.text}
            </span>
          )}
        </div>
      ))}
    </div>
  );
}
