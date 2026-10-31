import { Header } from "@/components/staff/Header";
import { StatisticsView } from "@/components/staff/statistics/StatisticsView";
import { defaultDeps } from "@/lib/deps";
import { requireStaffContext } from "@/lib/staff/current";
import type { SearchParams } from "@/lib/staff/overview";
import { loadStatistics } from "@/lib/staff/statistics-page";

/**
 * «Статистика»: correlation analysis of the team or one player (`?who=&m=&period=&from=&to=`). The selection
 * lives in the URL; Faceit is asked on the server, and only for the caller's roster.
 */
export default async function StatisticsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  // Layouts do not protect page data: the page checks the session itself.
  const ctx = await requireStaffContext();
  const page = await loadStatistics(defaultDeps(), ctx, await searchParams);
  return (
    <>
      <Header title="Статистика" />
      <StatisticsView page={page} />
    </>
  );
}
