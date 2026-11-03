import type { ReactNode } from "react";
import { getPlayerCount, requireStaffContext } from "@/lib/staff/current";
import { playersLabel } from "@/lib/staff/messages";
import styles from "@/app/staff/staff.module.css";

interface HeaderProps {
  title: string;
  /** Replaces «{team} · N игроков» next to the Staff badge. */
  meta?: string;
  /** Page-specific widgets shown at the right of the title. */
  children?: ReactNode;
}

/** The page header of the board: «Staff» badge, team and player count, then the title. */
export async function Header({ title, meta, children }: HeaderProps) {
  const ctx = await requireStaffContext();
  const count = meta === undefined ? await getPlayerCount() : 0;
  return (
    <header className={styles.header}>
      <div className={styles.headerText}>
        <span className={styles.headerMeta}>
          <span className={styles.staffBadge}>Staff</span>
          <span className={styles.headerTeam}>
            {meta ?? `${ctx.teamName} · ${playersLabel(count)}`}
          </span>
        </span>
        <h1 className={styles.title}>{title}</h1>
      </div>
      <div className={styles.headerSpacer} />
      {children}
    </header>
  );
}
