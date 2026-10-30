import { Header } from "@/components/staff/Header";
import { Roster } from "@/components/staff/players/Roster";
import { TeamManagement } from "@/components/staff/players/TeamManagement";
import { defaultDeps } from "@/lib/deps";
import { requireStaffContext } from "@/lib/staff/current";
import type { SearchParams } from "@/lib/staff/overview";
import { loadPlayers } from "@/lib/staff/players";

/** «Игроки»: today's readiness of the roster, and «Команда» (codes, members). Confirmations live in the URL. */
export default async function PlayersPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  // Layouts do not protect page data: the page checks the session itself.
  const ctx = await requireStaffContext();
  const page = await loadPlayers(defaultDeps(), ctx, await searchParams);
  return (
    <>
      <Header title="Игроки" />
      <Roster roster={page.roster} />
      <TeamManagement team={page.team} confirm={page.confirm} error={page.error} />
    </>
  );
}
