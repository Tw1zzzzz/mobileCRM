import type { MatrixView } from "@/lib/staff/statistics-view";
import styles from "./statistics.module.css";

const LABEL_COLUMN = 150;
const MIN_COLUMN = 78;
const COLUMN_WIDTH = 86;

function cellClass(kind: "r" | "diagonal" | "few"): string {
  return kind === "diagonal" ? `${styles.mxc} ${styles.mxDiag}` : kind === "few" ? `${styles.mxc} ${styles.mxFew}` : styles.mxc;
}

/**
 * «Таблица корреляций»: all pairs of the selected metrics. A `<details>`, open by default, so folding it away
 * needs no script; the summary shows «Свернуть таблицу» while open and «Показать таблицу» while closed.
 */
export function Matrix({ matrix }: { matrix: MatrixView }) {
  const n = matrix.head.length;
  return (
    <details open className={`${styles.card} ${styles.matrixBox}`}>
      <summary className={styles.summary}>
        <span className={styles.cardTitles}>
          <h2 className={styles.cardTitle}>Таблица корреляций</h2>
          <span className={styles.sub}>Все пары выбранных метрик. Голубой — прямая связь, оранжевый — обратная, ярче — сильнее</span>
        </span>
        <span className={`${styles.toggle} ${styles.whenOpen}`}>Свернуть таблицу</span>
        <span className={`${styles.toggle} ${styles.whenClosed}`}>Показать таблицу</span>
      </summary>
      <div className={styles.matrixScroll}>
        <div
          className={styles.mx}
          role="table"
          aria-label="Матрица корреляций"
          style={{ gridTemplateColumns: `${LABEL_COLUMN}px repeat(${n}, minmax(${MIN_COLUMN}px, 1fr))`, minWidth: `${LABEL_COLUMN + n * COLUMN_WIDTH}px` }}
        >
          <div role="row" className={styles.mxRow}>
            <span className={styles.mxh} />
            {matrix.head.map((name) => (
              <span key={name} role="columnheader" className={styles.mxh} title={name}>
                {name}
              </span>
            ))}
          </div>
          {matrix.rows.map((row) => (
            <div key={row.name} role="row" className={styles.mxRow}>
              <span role="rowheader" className={styles.mxr} title={row.name}>
                {row.name}
              </span>
              {row.cells.map((cell, i) => (
                <span
                  key={matrix.head[i]}
                  role="cell"
                  className={cellClass(cell.kind)}
                  style={cell.background === null ? undefined : { background: cell.background }}
                >
                  {cell.text}
                </span>
              ))}
            </div>
          ))}
        </div>
      </div>
    </details>
  );
}
