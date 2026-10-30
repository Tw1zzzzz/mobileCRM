import { redirect } from "next/navigation";
import { Sidebar } from "@/components/staff/Sidebar";
import { getSessionState } from "@/lib/staff/current";
import styles from "../staff.module.css";

/** The panel shell: only staff of a team get past it. Pages repeat the check with `requireStaffContext`. */
export default async function PanelLayout({ children }: { children: React.ReactNode }) {
  const state = await getSessionState();
  if (state.kind === "none") redirect("/staff/login");
  if (state.kind === "notStaff") redirect("/staff/no-access");
  return (
    <div className={styles.shell}>
      <Sidebar teamName={state.ctx.teamName} />
      <main className={styles.main}>{children}</main>
    </div>
  );
}
