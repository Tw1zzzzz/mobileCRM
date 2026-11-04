import Link from "next/link";
import { deleteGameAction } from "@/app/staff/actions";
import type { Game, GameSummary } from "@/lib/staff/games";
import { summaryHref, type SummaryParams } from "@/lib/staff/game-summary";
import { buildSummary, KIND_LABELS } from "@/lib/staff/game-summary-table";
import type { RosterPlayer } from "./draft";
import styles from "./game-stats.module.css";
import { PeriodFilter, SourceAndPlayerFilter } from "./SummaryFilters";
import { SummaryTable } from "./SummaryTable";

export const DELETE_QUESTION = "Удалить игру? Она пропадёт из статистики команды";

const shortDate = (day: string): string => day.split("-").reverse().slice(0, 2).join(".");

/** The question before a game is deleted: a second page view (`?delete=<id>`), so it works without a script. */
function ConfirmDelete({ game, params }: { game: Game; params: SummaryParams }) {
  return (
    <div id="confirm-delete" className={styles.confirm} role="group" aria-label="Подтверждение">
      <p className={styles.confirmText}>{DELETE_QUESTION}</p>
      <p className={styles.confirmGame}>
        {`${shortDate(game.playedOn)} · ${KIND_LABELS[game.kind]}${game.opponent ? ` · vs ${game.opponent}` : ""}`}
      </p>
      <div className={styles.confirmActions}>
        <form action={deleteGameAction}>
          <input type="hidden" name="id" value={game.id} />
          <input type="hidden" name="returnTo" value={summaryHref(params)} />
          <button type="submit" className={styles.btnDanger}>Удалить</button>
        </form>
        <Link href={summaryHref(params)} scroll={false} className={styles.btnGhost}>Отмена</Link>
      </div>
    </div>
  );
}

export interface PeriodSummaryProps {
  params: SummaryParams;
  roster: readonly RosterPlayer[];
  /** The team's games for the filters, newest first. */
  games: readonly GameSummary[];
  /** The game whose deletion is being confirmed, if any. */
  confirm: Game | null;
}

/** «Сводка за период»: the filters in the URL, the four tiles and the per-game table (or the empty state). */
export function PeriodSummary({ params, roster, games, confirm }: PeriodSummaryProps) {
  const summary = buildSummary(games, params.who);
  return (
    <section className={styles.card} aria-labelledby="gs-period">
      <div className={styles.sumHeader}>
        <div className={styles.sumHeaderText}>
          <h2 id="gs-period">Сводка за период</h2>
          <span className={styles.stepSub}>Итог за период и каждая игра отдельно — новые слева</span>
        </div>
        <PeriodFilter params={params} />
      </div>
      <SourceAndPlayerFilter params={params} roster={roster} />
      <div className={styles.periodTiles}>
        {summary.tiles.map((tile) => (
          <div key={tile.label} className={styles.tile}>
            <span>{tile.label}</span>
            <span>{tile.value}</span>
          </div>
        ))}
      </div>
      {confirm && <ConfirmDelete game={confirm} params={params} />}
      {games.length > 0 ? (
        <SummaryTable summary={summary} params={params} />
      ) : (
        <div className={styles.emptyState}>
          <span className={styles.emptyTitle}>Нет игр для этого фильтра</span>
          <span className={styles.emptySub}>Выберите другой источник или игрока</span>
        </div>
      )}
    </section>
  );
}
