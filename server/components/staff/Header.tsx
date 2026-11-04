import Link from "next/link";
import type { ReactNode } from "react";
import { getNickname, getPlayerCount, requireStaffContext } from "@/lib/staff/current";
import { playersLabel } from "@/lib/staff/messages";
import styles from "@/app/staff/staff.module.css";

interface HeaderProps {
  title: string;
  /** Replaces «{team} · N игроков» next to the Staff badge. */
  meta?: string;
  /** Page-specific widgets shown at the right of the title. */
  children?: ReactNode;
  /** Shown after «Профиль», at the far right (the profile page's «Выйти»). */
  trailing?: ReactNode;
}

/** The page header of the board: «Staff» badge, team and player count, the title, and «Профиль» at the top right. */
export async function Header({ title, meta, children, trailing }: HeaderProps) {
  const ctx = await requireStaffContext();
  const [count, nickname] = await Promise.all([meta === undefined ? getPlayerCount() : 0, getNickname()]);
  const initials = Array.from(nickname.trim()).slice(0, 2).join("").toUpperCase();
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
      <Link href="/staff/profile" className={styles.headerAccount} aria-label="Профиль и настройки">
        <span className={styles.avatar} aria-hidden="true">{initials}</span>
        <span className={styles.accountText}>
          <span className={styles.accountName}>{nickname}</span>
          <span className={styles.accountRole}>Профиль</span>
        </span>
      </Link>
      {trailing}
    </header>
  );
}
