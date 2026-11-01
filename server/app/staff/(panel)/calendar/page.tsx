import { Header } from "@/components/staff/Header";
import { CalendarView } from "@/components/staff/calendar/CalendarView";
import { defaultDeps } from "@/lib/deps";
import { requireStaffContext } from "@/lib/staff/current";
import { loadCalendarPage } from "@/lib/staff/calendar";
import type { SearchParams } from "@/lib/staff/overview";

/** Shared staff calendar. Personal entries are visible only to their creator. */
export default async function CalendarPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const ctx = await requireStaffContext();
  const page = await loadCalendarPage(defaultDeps(), ctx, await searchParams);
  return <><Header title="Календарь" /><CalendarView page={page} /></>;
}
