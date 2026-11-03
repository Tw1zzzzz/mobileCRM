import { Header } from "@/components/staff/Header";
import { HeaderActions } from "@/components/staff/lineups/HeaderActions";
import { LineupsView } from "@/components/staff/lineups/LineupsView";
import { defaultDeps } from "@/lib/deps";
import { requireStaffContext } from "@/lib/staff/current";
import { loadStaffLineups } from "@/lib/staff/lineups";

type SearchParams = Record<string, string | string[] | undefined>;

/** «Раскидки и скрипты»: the team's base of lineups and scripts. Filters, the open entry and the dialog live in the URL. */
export default async function LineupsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const ctx = await requireStaffContext();
  const page = await loadStaffLineups(defaultDeps(), ctx, await searchParams);
  return (
    <>
      <Header title="Раскидки и скрипты" meta={`${ctx.teamName} · база команды, видна всем игрокам`}>
        <HeaderActions filters={page.filters} />
      </Header>
      <LineupsView page={page} />
    </>
  );
}
