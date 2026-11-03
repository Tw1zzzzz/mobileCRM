import { EmptyTeam } from "@/components/staff/overview/EmptyTeam";
import { OverviewView } from "@/components/staff/overview/OverviewView";
import { ReadinessBadge } from "@/components/staff/overview/ReadinessBadge";
import { TodayCard } from "@/components/staff/overview/TodayCard";
import { Header } from "@/components/staff/Header";
import { defaultDeps } from "@/lib/deps";
import { requireStaffContext } from "@/lib/staff/current";
import { loadOverview, type SearchParams } from "@/lib/staff/overview";
import { loadTodayEvents } from "@/lib/staff/overview-today";

/** «Обзор»: the team's week on real data. Filters live in the URL (`?who=`, `?show=`). */
export default async function OverviewPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  // Layouts do not protect page data: the page checks the session itself.
  const ctx = await requireStaffContext();
  const deps = defaultDeps();
  const [page, today] = await Promise.all([loadOverview(deps, ctx, await searchParams), loadTodayEvents(deps, ctx)]);
  return (
    <>
      <Header title="Обзор">
        <TodayCard events={today} />
        <ReadinessBadge value={page.kind === "data" ? page.overview.teamReadiness : null} />
      </Header>
      {page.kind === "empty" ? (
        <EmptyTeam playerCode={page.playerCode} />
      ) : (
        <OverviewView overview={page.overview} players={page.players} filters={page.filters} />
      )}
    </>
  );
}
