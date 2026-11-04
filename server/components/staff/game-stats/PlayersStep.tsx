import type { ClipboardEvent } from "react";
import {
  CELLS, GAME_MAP_OPTIONS, absentRows, MAX_NICKNAME, MAX_ROWS, TEAM, activeMap, normalizeCell, readCell, visibleRows, withCell, withNickname,
  withAbsent, withPastedCells, withStandIn, withoutRow, type CellSpec, type Draft, type DraftRow,
} from "./draft";
import { rowFigures } from "./figures";
import styles from "./game-stats.module.css";
import { parsePaste, pasteCells } from "./paste";
import { Icon, StepHead, cx } from "./ui";

interface PlayersStepProps {
  draft: Draft;
  showErrors: boolean;
  onChange: (update: (draft: Draft) => Draft) => void;
}

const AUTO_COLUMNS = ["K/D", "KPR", "DPR", "Опен ±"] as const;

function cellLabel(row: DraftRow, spec: CellSpec): string {
  return `${row.nickname.trim() || "Запасной"}, ${spec.title}`;
}

/** Step 3 «Статистика игроков · {карта}»: a table with a line per player of the shown map. */
export function PlayersStep({ draft, showErrors, onChange }: PlayersStepProps) {
  const index = Math.min(draft.active, draft.maps.length - 1);
  const map = activeMap(draft);
  const rows = visibleRows(draft, map);
  const absent = absentRows(draft, map);
  const mapName = GAME_MAP_OPTIONS.find((option) => option.value === map.map)?.label ?? `карта ${index + 1}`;

  function paste(event: ClipboardEvent<HTMLInputElement>, row: number, col: number) {
    const grid = parsePaste(event.clipboardData.getData("text"));
    if (grid.length === 0) return;
    event.preventDefault();
    const cells = pasteCells(grid, { row, col }, { rows: rows.length, cols: CELLS.length });
    onChange((d) => withPastedCells(d, index, cells));
  }

  return (
    <section className={styles.card} aria-labelledby="gs-step3">
      <div className={styles.stepTop}>
        <StepHead n={3} id="gs-step3" title={`Статистика игроков · ${mapName}`} sub="Как таблица счёта: строка — игрок. Голубые колонки считаются сами" />
        <span className={styles.pasteHint}>
          <Icon>
            <rect x="8" y="3" width="12" height="14" rx="2" />
            <path d="M4 7v12a2 2 0 0 0 2 2h10" />
          </Icon>
          Строки из Excel или Google Таблиц можно вставить в любую ячейку — Ctrl+V
        </span>
      </div>
      <div className={styles.tableWrap}>
        <div role="table" aria-label="Статистика игроков на карте" className={styles.table}>
          <div role="row" className={cx(styles.gridRow, styles.gridHead)}>
            <span role="columnheader" className={cx(styles.gname, styles.gh)}>Игрок</span>
            {CELLS.map((spec) => (
              <span key={spec.key} role="columnheader" className={cx(styles.gh, spec.required && styles.ghReq)} title={spec.title}>
                {spec.label}
              </span>
            ))}
            {AUTO_COLUMNS.map((label) => (
              <span key={label} role="columnheader" className={cx(styles.gh, styles.ghAuto)}>{label}</span>
            ))}
          </div>
          {rows.map((row, rowIndex) => {
            const figures = rowFigures(map, row);
            return (
              <div key={row.key} role="row" className={styles.gridRow}>
                {row.playerId === null ? (
                  <span role="rowheader" className={styles.gname}>
                    <input
                      className={cx(styles.standIn, showErrors && row.nickname.trim() === "" && styles.standInBad)}
                      type="text"
                      maxLength={MAX_NICKNAME}
                      placeholder="Ник запасного"
                      aria-label="Ник запасного"
                      value={row.nickname}
                      onChange={(e) => onChange((d) => withNickname(d, index, row.key, e.target.value))}
                    />
                    <button
                      type="button"
                      className={styles.iconBtn}
                      aria-label="Убрать запасного"
                      title="Убрать запасного"
                      onClick={() => onChange((d) => withoutRow(d, index, row.key))}
                    >
                      <Icon path="M6 6l12 12M18 6L6 18" size={14} strokeWidth={2.4} />
                    </button>
                  </span>
                ) : (
                  <span role="rowheader" className={styles.gname}>
                    <span className={styles.gnameText}>{row.nickname}</span>
                    {draft.who === TEAM && (
                      <button
                        type="button"
                        className={styles.iconBtn}
                        aria-label={`${row.nickname}: не играл`}
                        title="Не играл"
                        onClick={() => onChange((d) => withAbsent(d, index, row.key, true))}
                      >
                        <Icon path="M6 6l12 12M18 6L6 18" size={14} strokeWidth={2.4} />
                      </button>
                    )}
                  </span>
                )}
                {CELLS.map((spec, col) => {
                  const value = row.cells[spec.key];
                  const state = readCell(spec.key, value);
                  const wrong = state.kind === "bad" || (showErrors && spec.required && state.kind === "empty");
                  return (
                    <span key={spec.key} role="cell" className={styles.gc}>
                      <input
                        className={cx(styles.cell, wrong && styles.cellErr)}
                        type="text"
                        inputMode="decimal"
                        placeholder="—"
                        aria-label={cellLabel(row, spec)}
                        aria-invalid={wrong || undefined}
                        value={value}
                        onChange={(e) => onChange((d) => withCell(d, index, row.key, spec.key, e.target.value))}
                        onBlur={(e) => {
                          const shown = normalizeCell(spec.key, e.target.value);
                          if (shown !== e.target.value) onChange((d) => withCell(d, index, row.key, spec.key, shown));
                        }}
                        onPaste={(e) => paste(e, rowIndex, col)}
                      />
                    </span>
                  );
                })}
                <span role="cell" className={styles.ga}>{figures.kd}</span>
                <span role="cell" className={styles.ga}>{figures.kpr}</span>
                <span role="cell" className={styles.ga}>{figures.dpr}</span>
                <span role="cell" className={styles.ga}>{figures.open}</span>
              </div>
            );
          })}
        </div>
      </div>
      {absent.length > 0 && (
        <div className={styles.absentRow}>
          <span>Не играл:</span>
          {absent.map((row) => (
            <button key={row.key} type="button" className={styles.chip} onClick={() => onChange((d) => withAbsent(d, index, row.key, false))}>
              {row.nickname} · Вернуть
            </button>
          ))}
        </div>
      )}
      <div className={styles.tableFoot}>
        <span><strong>K, D, A, ADR</strong> — обязательно</span>
        <span>FK / FD — первые убийства / смерти · Мульти — ср. мультикиллы · Ослепл. — ср. время ослепления, с</span>
        {draft.who === TEAM && (
          <button type="button" className={styles.btnGhost} disabled={rows.length >= MAX_ROWS} onClick={() => onChange((d) => withStandIn(d, index))}>
            <Icon path="M12 5v14M5 12h14" strokeWidth={2.4} />
            Добавить запасного
          </button>
        )}
      </div>
    </section>
  );
}
