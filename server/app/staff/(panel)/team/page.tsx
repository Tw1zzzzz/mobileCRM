import { Header } from "@/components/staff/Header";
import { TeamManagement } from "@/components/staff/players/TeamManagement";
import { defaultDeps } from "@/lib/deps";
import { requireStaffContext } from "@/lib/staff/current";
import type { SearchParams } from "@/lib/staff/overview";
import { loadTeamPage } from "@/lib/staff/players";

/** Team codes and member management, separate from the player roster. */
export default async function TeamPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const ctx = await requireStaffContext();
  const page = await loadTeamPage(defaultDeps(), ctx, await searchParams);
  return (
    <>
      <Header title="Команда" />
      <TeamManagement team={page.team} confirm={page.confirm} error={page.error} />
    </>
  );
}
