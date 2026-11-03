import Link from "next/link";
import styles from "./overview.module.css";

/** The «↗» button at the right of a card's title: opens the section the card summarises. */
export function OpenLink({ href, label }: { href: string; label: string }) {
  return (
    <Link href={href} className={styles.iconBtn} aria-label={label}>
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M7 17L17 7M8 7h9v9" />
      </svg>
    </Link>
  );
}
