import type { GameCardView } from "@/lib/staff/statistics-view";
import styles from "./statistics.module.css";

/** «Игровые показатели за период»: the five tiles of the board, from the same Faceit matches as the correlations. */
export function GameCard({ game }: { game: GameCardView }) {
  return (
    <section className={styles.card}>
      <div className={styles.cardTitles}>
        <h2 className={styles.cardTitle}>Игровые показатели за период</h2>
        <span className={styles.sub}>Матчи Faceit</span>
      </div>
      <dl className={styles.stats}>
        {game.tiles.map((tile) => (
          <div key={tile.label} className={styles.stat}>
            <dt className={styles.statLabel}>{tile.label}</dt>
            <dd className={styles.statValue}>{tile.value}</dd>
          </div>
        ))}
      </dl>
      {game.note !== null && <span className={styles.sub}>{game.note}</span>}
    </section>
  );
}
