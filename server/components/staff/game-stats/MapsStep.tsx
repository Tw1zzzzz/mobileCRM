import type { PistolResult } from "@/lib/staff/games-math";
import type { DraftCheck } from "./check";
import {
  GAME_MAP_OPTIONS, MAX_MAPS, activeMap, withActive, withMapChange, withNewMap, withoutMap, type Draft,
} from "./draft";
import { mapResultOf } from "./figures";
import styles from "./game-stats.module.css";
import { CHECK, Icon, StepHead, cx } from "./ui";

interface MapsStepProps {
  draft: Draft;
  check: DraftCheck;
  showErrors: boolean;
  onChange: (update: (draft: Draft) => Draft) => void;
}

const RESULT_VIEW = {
  win: { text: "Победа", className: styles.resWin },
  loss: { text: "Поражение", className: styles.resLoss },
  draw: { text: "Ничья", className: styles.resDraw },
} as const;

const PISTOLS = [
  { key: "pistol1", label: "Пистолетный · 1-я половина" },
  { key: "pistol2", label: "Пистолетный · 2-я половина" },
] as const;

const PISTOL_OPTIONS: readonly { value: PistolResult; label: string; className: string }[] = [
  { value: "won", label: "Выиграли", className: styles.optWon },
  { value: "lost", label: "Проиграли", className: styles.optLost },
];

const digits = (value: string): string => value.replace(/[^0-9]/g, "").slice(0, 2);

/** Step 2 «Карты и счёт»: the maps of the game; for the shown map its name, score, result and both pistols. */
export function MapsStep({ draft, check, showErrors, onChange }: MapsStepProps) {
  const index = Math.min(draft.active, draft.maps.length - 1);
  const map = activeMap(draft);
  const result = mapResultOf(map);
  const view = result === null ? null : RESULT_VIEW[result];
  const scoreBad = (value: string) => showErrors && value.trim() === "";
  return (
    <section className={styles.card} aria-labelledby="gs-step2">
      <StepHead n={2} id="gs-step2" title="Карты и счёт" sub="Каждая карта — отдельный матч. Победа, раунды и пистолетки считаются по счёту" />
      <div className={styles.mapTabs}>
        <div role="tablist" aria-label="Карты" className={styles.mapTabList}>
          {draft.maps.map((m, i) => {
            const label = GAME_MAP_OPTIONS.find((option) => option.value === m.map)?.label;
            const scored = m.our.trim() !== "" && m.their.trim() !== "";
            return (
              <button
                key={i}
                type="button"
                role="tab"
                aria-selected={i === index}
                className={cx(styles.mapTab, i === index && styles.mapTabOn)}
                onClick={() => onChange((d) => withActive(d, i))}
              >
                <span className={styles.mapTabLabel}>
                  Карта {i + 1}
                  {check.maps[i].complete ? (
                    <span role="img" aria-label="заполнено" style={{ display: "inline-flex", color: "var(--cyan)" }}>
                      <Icon path={CHECK} size={14} strokeWidth={3} />
                    </span>
                  ) : (
                    <span role="img" aria-label="есть пропуски" className={styles.dot} />
                  )}
                </span>
                <span className={styles.mapTabSub}>{`${label ?? "Не выбрана"}${scored ? `  ${m.our}:${m.their}` : ""}`}</span>
              </button>
            );
          })}
        </div>
        <button
          type="button"
          className={cx(styles.mapTab, styles.mapTabAdd)}
          disabled={draft.maps.length >= MAX_MAPS}
          onClick={() => onChange(withNewMap)}
        >
          <Icon path="M12 5v14M5 12h14" strokeWidth={2.4} />
          Добавить карту
        </button>
      </div>

      <div className={styles.mapPanel}>
        <div className={styles.fld}>
          <span className={styles.lbl}>Карта</span>
          <div role="group" aria-label="Выбор карты" className={styles.chips}>
            {GAME_MAP_OPTIONS.map((option) => (
              <button
                key={option.value}
                type="button"
                className={cx(styles.chip, map.map === option.value && styles.chipOn)}
                aria-pressed={map.map === option.value}
                onClick={() => onChange((d) => withMapChange(d, index, { map: option.value }))}
              >
                {option.label}
              </button>
            ))}
          </div>
        </div>

        <div className={styles.scoreRow}>
          <div className={styles.fld}>
            <span className={styles.lbl}>Счёт</span>
            <div className={styles.scoreFields}>
              <div className={styles.scoreCol}>
                <input
                  id="gs-score-our"
                  className={cx(styles.score, scoreBad(map.our) && styles.scoreBad)}
                  type="text"
                  inputMode="numeric"
                  placeholder="0"
                  aria-label="Наши раунды"
                  value={map.our}
                  onChange={(e) => onChange((d) => withMapChange(d, index, { our: digits(e.target.value) }))}
                />
                <span>Мы</span>
              </div>
              <span className={styles.colon} aria-hidden="true">:</span>
              <div className={styles.scoreCol}>
                <input
                  id="gs-score-their"
                  className={cx(styles.score, scoreBad(map.their) && styles.scoreBad)}
                  type="text"
                  inputMode="numeric"
                  placeholder="0"
                  aria-label="Раунды соперника"
                  value={map.their}
                  onChange={(e) => onChange((d) => withMapChange(d, index, { their: digits(e.target.value) }))}
                />
                <span>Соперник</span>
              </div>
            </div>
          </div>
          <div className={styles.resultCell}>
            <span className={cx(styles.res, view ? view.className : styles.resNone)}>
              {view ? view.text : "Результат появится после счёта"}
            </span>
          </div>
          <div className={styles.rowSpacer} />
          {draft.maps.length > 1 && (
            <button type="button" className={cx(styles.btnGhost, styles.removeMap)} onClick={() => onChange((d) => withoutMap(d, index))}>
              <Icon path="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />
              Удалить карту
            </button>
          )}
        </div>

        <div className={styles.pistols}>
          {PISTOLS.map((pistol) => (
            <div key={pistol.key} className={styles.fld}>
              <span className={styles.lbl}>{pistol.label}</span>
              <div role="group" aria-label={pistol.label} className={styles.pistolOpts}>
                {PISTOL_OPTIONS.map((option) => {
                  const on = map[pistol.key] === option.value;
                  return (
                    <button
                      key={option.value}
                      type="button"
                      className={cx(styles.opt, on && option.className)}
                      aria-pressed={on}
                      onClick={() => onChange((d) => withMapChange(d, index, { [pistol.key]: on ? "" : option.value }))}
                    >
                      {option.label}
                    </button>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
