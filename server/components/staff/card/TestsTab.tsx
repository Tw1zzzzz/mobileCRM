import type { TestsData } from "@/lib/staff/card";
import type { ChartLine, TestsChart } from "@/lib/staff/card-charts";
import { NO_DATA } from "@/lib/staff/overview-format";
import styles from "./card.module.css";

const SCORE_COLOR = "#3fd0ff";
const STATE_COLOR = "#ffaa3c";

function Line({ line, color, dashed }: { line: ChartLine; color: string; dashed?: boolean }) {
  return (
    <>
      <path
        d={line.line}
        fill="none"
        stroke={color}
        strokeWidth="3"
        strokeDasharray={dashed ? "8 6" : undefined}
        strokeLinejoin="round"
        strokeLinecap="round"
      />
      <path d={line.dots} fill={color} stroke="none" />
    </>
  );
}

function Chart({ chart }: { chart: TestsChart }) {
  return (
    <>
      <div className={styles.legendRow}>
        <span className={styles.legendItem}>
          <span className={styles.swatchScore} />
          Score
        </span>
        <span className={styles.legendItem}>
          <span className={styles.swatchState} />
          Индекс состояния
        </span>
      </div>
      <svg viewBox="0 0 900 220" width="100%" role="img" aria-label={chart.aria} className={styles.chart}>
        <g stroke="#26304a">
          <line x1="40" y1="20" x2="890" y2="20" />
          <line x1="40" y1="100" x2="890" y2="100" />
          <line x1="40" y1="180" x2="890" y2="180" />
        </g>
        <g fill="#7d89a8" fontSize="12" fontFamily="Onest, sans-serif">
          <text x="4" y="24">100</text>
          <text x="10" y="104">50</text>
          <text x="22" y="184">0</text>
        </g>
        <Line line={chart.score} color={SCORE_COLOR} />
        <Line line={chart.state} color={STATE_COLOR} dashed />
      </svg>
    </>
  );
}

/** «Тесты»: the score and state-index chart and the history of the tests in the period. */
export function TestsTab({ tests }: { tests: TestsData }) {
  const { chart, rows, total } = tests;
  return (
    <div className={styles.body}>
      {chart ? <Chart chart={chart} /> : <p className={styles.empty}>{NO_DATA}</p>}
      <div className={styles.table}>
        <div role="table" aria-label="История тестов" className={styles.tableTests}>
          <div role="row" className={`${styles.row} ${styles.rowTests} ${styles.head}`}>
            <span role="columnheader">Дата</span>
            <span role="columnheader">Тест</span>
            <span role="columnheader">Score</span>
            <span role="columnheader">Индекс состояния</span>
          </div>
          {rows.length === 0 ? (
            <div role="row" className={styles.row}>
              <span role="cell">{NO_DATA}</span>
            </div>
          ) : (
            rows.map((row, i) => (
              <div key={i} role="row" className={`${styles.row} ${styles.rowTests}`}>
                <span role="cell">{row.date}</span>
                <span role="cell" className={styles.strong}>{row.test}</span>
                <span role="cell" className={styles.heavy}>{row.score}</span>
                <span role="cell">{row.state}</span>
              </div>
            ))
          )}
        </div>
      </div>
      {total > rows.length && <p className={styles.cut}>Показаны последние {rows.length} из {total}</p>}
    </div>
  );
}
