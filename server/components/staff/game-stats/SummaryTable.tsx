import Link from "next/link";
import { GAME_STATS_PATH, summaryHref, type SummaryParams } from "@/lib/staff/game-summary";
import type { Summary } from "@/lib/staff/game-summary-table";
import styles from "./game-stats.module.css";

/** The board's table: «Показатель», the whole period, then every game (newest first) with «Изменить» and «Удалить». */
export function SummaryTable({ summary, params }: { summary: Summary; params: SummaryParams }) {
  return (
    <div className={styles.tableWrap}>
      <table className={styles.sumTable} aria-label="Показатели по играм">
        <thead>
          <tr>
            <th scope="col" className={`${styles.sumCell} ${styles.sumLabel} ${styles.sumCorner}`}>Показатель</th>
            {summary.columns.map((column) => (
              <th key={column.gameId ?? "period"} scope="col" className={`${styles.sumCell} ${styles.sumHead}`}>
                <span className={styles.sumTitle}>{column.title}</span>
                <span className={styles.sumSub}>{column.sub}</span>
                {column.gameId !== null && (
                  <span className={styles.sumActions}>
                    <Link href={`${GAME_STATS_PATH}?edit=${column.gameId}`} aria-label={`Изменить игру ${column.dateLabel}`} className={styles.sumLink}>
                      Изменить
                    </Link>
                    <Link href={summaryHref(params, {}, column.gameId)} aria-label={`Удалить игру ${column.dateLabel}`} className={`${styles.sumLink} ${styles.sumDanger}`} scroll={false}>
                      Удалить
                    </Link>
                  </span>
                )}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {summary.rows.map((row) =>
            row.kind === "group" ? (
              <tr key={`g-${row.label}`}>
                <th scope="colgroup" colSpan={summary.columns.length + 1} className={styles.sumGroup}>{row.label}</th>
              </tr>
            ) : (
              <tr key={row.label} className={styles.sumRow}>
                <th scope="row" className={`${styles.sumCell} ${styles.sumLabel}`}>{row.label}</th>
                {row.cells.map((cell, i) => (
                  <td key={summary.columns[i].gameId ?? "period"} className={i === 0 ? `${styles.sumCell} ${styles.sumTotal}` : styles.sumCell}>{cell}</td>
                ))}
              </tr>
            ),
          )}
        </tbody>
      </table>
    </div>
  );
}
