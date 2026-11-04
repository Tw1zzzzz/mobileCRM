import { GAME_KIND_OPTIONS, MAX_OPPONENT, TEAM, withField, withWho, type Draft, type RosterPlayer } from "./draft";
import styles from "./game-stats.module.css";
import { Icon, StepHead, cx } from "./ui";

interface InfoStepProps {
  draft: Draft;
  roster: readonly RosterPlayer[];
  onChange: (update: (draft: Draft) => Draft) => void;
}

/** Step 1 «Игра»: type, date, opponent and who played. */
export function InfoStep({ draft, roster, onChange }: InfoStepProps) {
  const single = draft.who !== TEAM;
  return (
    <section className={styles.card} aria-labelledby="gs-step1">
      <StepHead n={1} id="gs-step1" title="Игра" sub="Что и когда играли" />
      <div className={styles.infoGrid}>
        <div className={styles.fld}>
          <span className={styles.lbl}>Тип</span>
          <div role="group" aria-label="Тип игры" className={styles.segwrap}>
            {GAME_KIND_OPTIONS.map((option) => (
              <button
                key={option.value}
                type="button"
                className={cx(styles.segBtn, draft.kind === option.value && styles.segOn)}
                aria-pressed={draft.kind === option.value}
                onClick={() => onChange((d) => withField(d, "kind", option.value))}
              >
                {option.label}
              </button>
            ))}
          </div>
        </div>
        <div className={styles.fld}>
          <label htmlFor="gs-date">Дата</label>
          <div className={styles.dateWrap}>
            <span className={styles.dateIcon} aria-hidden="true">
              <Icon size={16}>
                <rect x="3" y="5" width="18" height="16" rx="2" />
                <path d="M3 10h18M8 3v4M16 3v4" />
              </Icon>
            </span>
            <input
              id="gs-date"
              className={cx(styles.inp, styles.inpDate)}
              type="date"
              value={draft.playedOn}
              onChange={(e) => onChange((d) => withField(d, "playedOn", e.target.value))}
              onClick={(e) => {
                try {
                  e.currentTarget.showPicker?.();
                } catch {
                  /* the browser has no picker, or refuses it here: the field still works by typing */
                }
              }}
            />
            <span className={styles.dateChev} aria-hidden="true">
              <Icon path="M6 9l6 6 6-6" size={16} strokeWidth={2.2} />
            </span>
          </div>
        </div>
        <div className={styles.fld}>
          <label htmlFor="gs-opp">
            Соперник <span className={styles.optional}>— по желанию</span>
          </label>
          <input
            id="gs-opp"
            className={styles.inp}
            type="text"
            maxLength={MAX_OPPONENT}
            placeholder="Название команды"
            value={draft.opponent}
            onChange={(e) => onChange((d) => withField(d, "opponent", e.target.value))}
          />
        </div>
        <div className={styles.fld}>
          <span className={styles.lbl}>Кто играл</span>
          <div role="group" aria-label="Состав" className={styles.segwrap}>
            <button
              type="button"
              className={cx(styles.segBtn, !single && styles.segOn)}
              aria-pressed={!single}
              onClick={() => onChange((d) => withWho(d, TEAM))}
            >
              Команда · {roster.length}
            </button>
            <button
              type="button"
              className={cx(styles.segBtn, single && styles.segOn)}
              aria-pressed={single}
              disabled={roster.length === 0}
              onClick={() => onChange((d) => (d.who === TEAM ? withWho(d, roster[0].id) : d))}
            >
              Один игрок
            </button>
          </div>
          {single && (
            <select
              className={styles.inp}
              aria-label="Игрок"
              value={draft.who}
              onChange={(e) => onChange((d) => withWho(d, e.target.value))}
            >
              {roster.map((player) => (
                <option key={player.id} value={player.id}>{player.nickname}</option>
              ))}
            </select>
          )}
        </div>
      </div>
    </section>
  );
}
