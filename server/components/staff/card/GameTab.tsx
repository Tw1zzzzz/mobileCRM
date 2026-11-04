import type { GameView } from "@/lib/staff/card-game";
import { GAME_ROWS } from "@/lib/staff/card-site";
import { FaceitNotice } from "./FaceitNotice";
import styles from "./card.module.css";

/** The player's hand-entered games of the period (pracc / tournament), newest first. */
function GameList({ game }: { game: GameView }) {
  return (
    <section className={styles.tileGroup}>
      <h3 className={styles.grp}>Игры</h3>
      <div className={styles.table}>
        <div role="table" aria-label="Игры игрока" className={styles.tableInner}>
          <div role="row" className={`${styles.row} ${styles.rowGames} ${styles.head}`}>
            <span role="columnheader">Дата</span>
            <span role="columnheader">Источник</span>
            <span role="columnheader">Соперник</span>
            <span role="columnheader">Карты</span>
            <span role="columnheader">K/D</span>
          </div>
          {game.games.map((row, i) => (
            <div key={i} role="row" className={`${styles.row} ${styles.rowGames}`}>
              <span role="cell" className={styles.strong}>{row.date}</span>
              <span role="cell">{row.source}</span>
              <span role="cell">{row.opponent}</span>
              <span role="cell">{row.maps}</span>
              <span role="cell" className={styles.heavy}>{row.kd}</span>
            </div>
          ))}
        </div>
      </div>
      {game.gamesTotal > GAME_ROWS && (
        <p className={styles.cut}>Показаны последние {GAME_ROWS} игр из {game.gamesTotal}</p>
      )}
    </section>
  );
}

/** «Игра»: K/D, analytic metrics and match/round counts of the period from the chosen source(s). */
export function GameTab({ game }: { game: GameView }) {
  return (
    <div className={styles.body}>
      <FaceitNotice notice={game.notice} />
      {game.coverage !== null && <p className={styles.cut}>{game.coverage}</p>}
      {game.emptyPeriod !== null && <p className={styles.cut}>{game.emptyPeriod}</p>}
      {game.groups.map((group, i) => (
        <section key={group.title} className={styles.tileGroup}>
          <h3 className={styles.grp}>{group.title}</h3>
          <dl className={i === 0 ? `${styles.tiles} ${styles.tilesNarrow}` : styles.tiles}>
            {group.tiles.map((tile) => (
              <div key={tile.label} className={styles.tile}>
                <dt>{tile.label}</dt>
                <dd>{tile.value}</dd>
              </div>
            ))}
          </dl>
        </section>
      ))}
      {game.games.length > 0 && <GameList game={game} />}
    </div>
  );
}
