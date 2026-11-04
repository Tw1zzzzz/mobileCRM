import { redirect } from "next/navigation";
import { Sidebar } from "@/components/staff/Sidebar";
import { getNickname, getSessionState } from "@/lib/staff/current";
import styles from "../staff.module.css";

/** The panel shell: only staff of a team get past it. Pages repeat the check with `requireStaffContext`. */
export default async function PanelLayout({ children }: { children: React.ReactNode }) {
  const state = await getSessionState();
  if (state.kind === "none") redirect("/staff/login");
  if (state.kind === "notStaff") redirect("/staff/no-access");
  const nickname = await getNickname();
  return (
    <div className={styles.shell}>
      <Sidebar teamName={state.ctx.teamName} nickname={nickname} />
      <main className={styles.main}>{children}</main>
    </div>
  );
}
