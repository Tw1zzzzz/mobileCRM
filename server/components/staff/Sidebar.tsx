import Link from "next/link";
import { NavLinks } from "@/components/staff/NavLinks";
import { SignOutButton } from "@/components/staff/SignOutButton";
import styles from "@/app/staff/staff.module.css";

/** «Меню Staff»: on a phone (≤ 900 px) the same markup becomes a menu along the top. */
export function Sidebar({ teamName }: { teamName: string }) {
  const initials = Array.from(teamName.trim()).slice(0, 2).join("").toUpperCase();
  return (
    <nav aria-label="Меню Staff" className={styles.sidebar}>
      <Link href="/staff/overview" className={styles.brand}>
        <span className={styles.brandMark}>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#FFFFFF" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M4 19L10 5l4 9 2-4 4 9" />
          </svg>
        </span>
        <span className={styles.brandText}>
          <span className={styles.brandEyebrow}>Pulse</span>
          <span className={styles.brandName}>Кабинет стаффа</span>
        </span>
      </Link>
      <NavLinks />
      <div className={styles.sidebarFoot}>
        <div className={styles.account}>
          <span className={styles.avatar} aria-hidden="true">{initials}</span>
          <span className={styles.accountText}>
            <span className={styles.accountName}>{teamName}</span>
            <span className={styles.accountRole}>Staff</span>
          </span>
        </div>
        <SignOutButton />
      </div>
    </nav>
  );
}
