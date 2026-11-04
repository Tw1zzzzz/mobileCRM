import Link from "next/link";
import { NavLinks } from "@/components/staff/NavLinks";
import { ProfileCard } from "@/components/staff/ProfileCard";
import styles from "@/app/staff/staff.module.css";

/** «Меню Staff» of the design: on a phone (≤ 900 px) the same markup becomes a menu along the top. */
export function Sidebar({ teamName, nickname }: { teamName: string; nickname: string }) {
  return (
    <nav aria-label="Меню Staff" className={styles.sidebar}>
      <Link href="/staff/overview" className={styles.brand}>
        <span className={styles.brandMark}>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#FFFFFF" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M4 19L10 5l4 9 2-4 4 9" />
          </svg>
        </span>
        <span className={styles.brandText}>
          <span className={styles.brandEyebrow}>Atlant Technology</span>
          <span className={styles.brandName}>Performance CRM</span>
        </span>
      </Link>
      <NavLinks />
      <div className={styles.sidebarFoot}>
        <ProfileCard nickname={nickname} role={`Staff · ${teamName}`} />
      </div>
    </nav>
  );
}
