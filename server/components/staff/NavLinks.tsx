"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { NAV_GROUPS, isActive, type NavItem } from "@/lib/staff/nav";
import styles from "@/app/staff/staff.module.css";

function Icon({ path }: { path: string }) {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={path} />
    </svg>
  );
}

function Item({ item, pathname }: { item: NavItem; pathname: string }) {
  if (item.href === null) {
    return (
      <span className={`${styles.navItem} ${styles.navSoon}`} aria-disabled="true">
        <Icon path={item.icon} />
        {item.name}
        <span className={styles.soon}>Скоро</span>
      </span>
    );
  }
  const on = isActive(item, pathname);
  return (
    <Link href={item.href} className={on ? `${styles.navItem} ${styles.navOn}` : styles.navItem} aria-current={on ? "page" : undefined}>
      <Icon path={item.icon} />
      {item.name}
    </Link>
  );
}

/** The sidebar entries of «Меню Staff»; a client component only to know the current path. */
export function NavLinks() {
  const pathname = usePathname();
  return (
    <>
      {NAV_GROUPS.map((group, index) => (
        <div key={group.title ?? index} className={styles.navGroup}>
          {group.title !== null && <span className={styles.navTitle}>{group.title}</span>}
          {group.items.map((item) => (
            <Item key={item.name} item={item} pathname={pathname} />
          ))}
        </div>
      ))}
    </>
  );
}
