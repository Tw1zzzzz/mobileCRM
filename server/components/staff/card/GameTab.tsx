import type { GameView } from "@/lib/staff/card-game";
import { FaceitNotice } from "./FaceitNotice";
import styles from "./card.module.css";

/** «Игра»: K/D, analytic metrics and match/round counts of the period from Faceit. */
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
    </div>
  );
}
