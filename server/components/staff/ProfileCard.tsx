"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import styles from "@/app/staff/staff.module.css";

const PROFILE_PATH = "/staff/profile";

/** The board's profile card: avatar, nickname, role line and a gear; highlighted on the profile page. */
export function ProfileCard({ nickname, role }: { nickname: string; role: string }) {
  const pathname = usePathname();
  const on = pathname === PROFILE_PATH || pathname.startsWith(`${PROFILE_PATH}/`);
  const initials = Array.from(nickname.trim()).slice(0, 2).join("").toUpperCase();
  return (
    <Link
      href={PROFILE_PATH}
      className={on ? `${styles.account} ${styles.accountOn}` : styles.account}
      aria-label="Профиль и настройки"
      aria-current={on ? "page" : undefined}
    >
      <span className={styles.avatar} aria-hidden="true">{initials}</span>
      <span className={styles.accountText}>
        <span className={styles.accountName}>{nickname}</span>
        <span className={styles.accountRole}>{role}</span>
      </span>
      <svg className={styles.accountGear} width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#A3AECB" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6zM12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1" />
      </svg>
    </Link>
  );
}
