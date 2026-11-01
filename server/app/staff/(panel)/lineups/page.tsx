import { Header } from "@/components/staff/Header";
import { LineupsView } from "@/components/staff/lineups/LineupsView";
import { defaultDeps } from "@/lib/deps";
import { requireStaffContext } from "@/lib/staff/current";
import { loadStaffLineups } from "@/lib/staff/lineups";

type SearchParams = Record<string, string | string[] | undefined>;

export default async function LineupsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const ctx = await requireStaffContext();
  const page = await loadStaffLineups(defaultDeps(), ctx, await searchParams);
  return <><Header title="Раскидки и скрипты" /><LineupsView page={page} /></>;
}
