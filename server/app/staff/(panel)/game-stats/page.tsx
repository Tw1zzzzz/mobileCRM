import { GameForm } from "@/components/staff/game-stats/GameForm";
import { draftFromGame } from "@/components/staff/game-stats/draft";
import styles from "@/components/staff/game-stats/game-stats.module.css";
import { Header } from "@/components/staff/Header";
import { defaultDeps } from "@/lib/deps";
import { rosterMembers } from "@/lib/staff/access";
import { requireStaffContext } from "@/lib/staff/current";
import { GAME_ERRORS } from "@/lib/staff/game-forms";
import { getGame } from "@/lib/staff/games";
import { STAFF_TIME_ZONE, dayOf } from "@/lib/staff/metrics";

export const metadata = { title: "Игровая статистика — кабинет стаффа" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined): string | undefined => (Array.isArray(value) ? value[0] : value);

/**
 * «Игровая статистика»: the team's games, typed in by hand. `?edit=<id>` opens a saved game of the team in the
 * form; another team's id (or none) shows the empty form with a notice.
 */
export default async function GameStatsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const ctx = await requireStaffContext();
  const deps = defaultDeps();
  const params = await searchParams;
  const roster = await rosterMembers(deps, ctx);
  const editId = first(params.edit);
  const game = editId === undefined ? null : await getGame(deps, ctx, editId);
  const missing = first(params.error) === "not-found" || (editId !== undefined && game === null);
  return (
    <>
      <Header title="Игровая статистика" />
      <div className={styles.page}>
        <p className={styles.lead}>Внесите игру по картам — итоги, проценты и сводка посчитаются сами.</p>
        {missing && (
          <p role="alert" className={styles.banner}>
            {GAME_ERRORS.notFound}
          </p>
        )}
        <GameForm
          key={game?.id ?? "new"}
          teamId={ctx.teamId}
          roster={roster}
          today={dayOf(deps.now(), STAFF_TIME_ZONE)}
          initial={game ? draftFromGame(game, roster) : null}
        />
        <section className={styles.summarySection} aria-labelledby="gs-period">
          <h2 id="gs-period">Сводка за период</h2>
          <span className={styles.soon}>Скоро</span>
        </section>
      </div>
    </>
  );
}
