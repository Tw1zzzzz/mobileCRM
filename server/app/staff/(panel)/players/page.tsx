import { CardFrame } from "@/components/staff/card/CardFrame";
import { Header } from "@/components/staff/Header";
import { PeriodChips, Roster } from "@/components/staff/players/Roster";
import { TeamManagement } from "@/components/staff/players/TeamManagement";
import { defaultDeps } from "@/lib/deps";
import { loadCard } from "@/lib/staff/card";
import { requireStaffContext } from "@/lib/staff/current";
import type { SearchParams } from "@/lib/staff/overview";
import { loadPlayers } from "@/lib/staff/players";

/**
 * «Игроки»: today's readiness of the roster, the selected player's card (`?player=&period=&tab=&type=&src=&day=`)
 * and «Команда» (codes, members). Selections and confirmations live in the URL.
 */
export default async function PlayersPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  // Layouts do not protect page data: the page checks the session itself.
  const ctx = await requireStaffContext();
  const params = await searchParams;
  const deps = defaultDeps();
  const [page, card] = await Promise.all([loadPlayers(deps, ctx, params), loadCard(deps, ctx, params)]);
  const selection = card.kind === "card" ? card.params : null;
  return (
    <>
      <Header title="Игроки">{selection && <PeriodChips params={selection} />}</Header>
      <Roster roster={page.roster} params={selection} />
      <CardFrame card={card} />
      <TeamManagement team={page.team} confirm={page.confirm} error={page.error} />
    </>
  );
}
