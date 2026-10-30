import { Header } from "@/components/staff/Header";
import { requireStaffContext } from "@/lib/staff/current";

/** Placeholder so the shell and its redirects work; Task 6 replaces this page with the real «Обзор». */
export default async function OverviewPage() {
  await requireStaffContext();
  return <Header title="Обзор" />;
}
