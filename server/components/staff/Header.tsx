import type { ReactNode } from "react";
import { getPlayerCount, requireStaffContext } from "@/lib/staff/current";
import { playersLabel } from "@/lib/staff/messages";
import styles from "@/app/staff/staff.module.css";

interface HeaderProps {
  title: string;
  /** Page-specific widgets shown at the right of the title. */
  children?: ReactNode;
}

/** The page header of the board: «Staff» badge, team and player count, then the title. */
export async function Header({ title, children }: HeaderProps) {
  const ctx = await requireStaffContext();
  const count = await getPlayerCount();
  return (
    <header className={styles.header}>
      <div className={styles.headerText}>
        <span className={styles.headerMeta}>
          <span className={styles.staffBadge}>Staff</span>
          <span className={styles.headerTeam}>
            {ctx.teamName} · {playersLabel(count)}
          </span>
        </span>
        <h1 className={styles.title}>{title}</h1>
      </div>
      <div className={styles.headerSpacer} />
      {children}
    </header>
  );
}
