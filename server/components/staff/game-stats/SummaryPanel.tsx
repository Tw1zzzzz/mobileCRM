import { optionalNote, type DraftCheck } from "./check";
import type { LiveSummary } from "./figures";
import styles from "./game-stats.module.css";
import { CHECK, Icon, cx } from "./ui";

export interface Message {
  tone: "error" | "ok";
  text: string;
}

interface SummaryPanelProps {
  summary: LiveSummary;
  check: DraftCheck;
  message: Message | null;
  pending: boolean;
  /** An existing game is being edited: there is no draft to keep or clear. */
  editing: boolean;
  onSave: () => void;
  onClear: () => void;
}

/** The side column: «Итог игры · считается сам», «Перед сохранением» with the save button and the draft note. */
export function SummaryPanel({ summary, check, message, pending, editing, onSave, onClear }: SummaryPanelProps) {
  const note = optionalNote(check.optionalEmpty);
  return (
    <aside className={styles.aside} aria-label="Итог игры">
      <section className={cx(styles.card, styles.summary)}>
        <div className={styles.summaryHead}>
          <span className={styles.eyebrow}>Итог игры · считается сам</span>
          <span className={styles.bigScore}>{summary.maps}</span>
          <span className={styles.stepSub}>{summary.mapsSub}</span>
        </div>
        <div className={styles.tiles}>
          <div className={styles.tile}><span>Win Rate</span><span>{summary.winRate}</span></div>
          <div className={styles.tile}><span>Round Win Rate</span><span>{summary.roundWinRate}</span></div>
          <div className={styles.tile}><span>Раунды W–L</span><span>{summary.rounds}</span></div>
          <div className={styles.tile}><span>Pistol Win Rate</span><span>{summary.pistolWinRate}</span></div>
          <div className={styles.tile}><span>AVG Rounds Won</span><span>{summary.avgRoundsWon}</span></div>
          <div className={styles.tile}><span>AVG Rounds Lost</span><span>{summary.avgRoundsLost}</span></div>
          <div className={cx(styles.tile, styles.tileWide)}><span>{summary.kdLabel}</span><span>{summary.kd}</span></div>
        </div>
      </section>

      <section className={cx(styles.card, styles.checkCard)}>
        <h2 className={styles.checkTitle}>Перед сохранением</h2>
        {check.issues.length > 0 ? (
          <ul className={styles.issues}>
            {check.issues.map((issue) => (
              <li key={issue} className={styles.issue}>{issue}</li>
            ))}
          </ul>
        ) : (
          <span className={styles.allGood}>
            <Icon path={CHECK} size={18} strokeWidth={2.6} />
            Всё обязательное заполнено
          </span>
        )}
        {note && <span className={styles.optionalNote}>{note}</span>}
        <button type="button" className={styles.btnPrimary} disabled={pending} onClick={onSave}>
          <Icon path={CHECK} size={18} strokeWidth={2.6} />
          Сохранить игру
        </button>
        <p role="status" className={cx(styles.message, message?.tone === "ok" ? styles.messageOk : styles.messageError)}>
          {message?.text}
        </p>
        {!editing && (
          <div className={styles.draftRow}>
            <span className={styles.draftNote}>
              <Icon size={16}>
                <path d="M20 17.6A5 5 0 0 0 18 8h-1.3A8 8 0 1 0 4 16.3" />
                <path d="M9 17l3 3 3-3M12 12v8" />
              </Icon>
              Черновик сохраняется автоматически
            </span>
            <button type="button" className={cx(styles.btnGhost, styles.smallBtn)} onClick={onClear}>Очистить</button>
          </div>
        )}
      </section>
    </aside>
  );
}
