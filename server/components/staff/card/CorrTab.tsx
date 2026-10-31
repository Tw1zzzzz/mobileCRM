import type { CorrView } from "@/lib/staff/card-game";
import { NO_DATA } from "@/lib/staff/overview-format";
import { FaceitNotice } from "./FaceitNotice";
import styles from "./card.module.css";

/** «Корреляции»: the player's state against the game, one r per cell, and the strongest pair underneath. */
export function CorrTab({ corr }: { corr: CorrView }) {
  const hasMatrix = corr.rows.length > 0;
  return (
    <div className={styles.body}>
      <FaceitNotice notice={corr.notice} />
      {hasMatrix && (
        <>
          <span className={styles.sub}>
            Как состояние игрока связано с игрой за период. Голубой — прямая связь, оранжевый — обратная, ярче — сильнее
          </span>
          {corr.coverage !== null && <p className={styles.cut}>{corr.coverage}</p>}
          <div className={styles.matrixScroll}>
            <div role="table" aria-label="Таблица корреляций" className={styles.matrix}>
              <div role="row" className={styles.mxRow}>
                <span role="presentation" className={styles.mxh} />
                {corr.head.map((name) => (
                  <span key={name} role="columnheader" className={styles.mxh}>
                    {name}
                  </span>
                ))}
              </div>
              {corr.rows.map((row) => (
                <div key={row.name} role="row" className={styles.mxRow}>
                  <span role="rowheader" className={styles.mxr}>
                    {row.name}
                  </span>
                  {row.cells.map((cell, i) => (
                    <span
                      key={corr.head[i]}
                      role="cell"
                      className={cell.background === null ? `${styles.mxc} ${styles.mxcEmpty}` : styles.mxc}
                      style={cell.background === null ? undefined : { background: cell.background }}
                    >
                      {cell.text}
                    </span>
                  ))}
                </div>
              ))}
            </div>
          </div>
          <div className={styles.corrNote}>
            <span className={styles.corrLead}>Главное: </span>
            {corr.note ?? NO_DATA}
          </div>
        </>
      )}
    </div>
  );
}
