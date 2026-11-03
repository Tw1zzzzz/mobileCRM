import Link from "next/link";
import type { LineupFilter } from "@/lib/staff/lineups";
import { lineupsHref } from "./meta";
import styles from "./lineups.module.css";

function Plus() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" aria-hidden="true">
      <path d="M12 5v14M5 12h14" />
    </svg>
  );
}

/** «+ Раскидка» and «+ Скрипт» at the right of the page title: they open the dialog, keeping the open entry. */
export function HeaderActions({ filters }: { filters: LineupFilter }) {
  return (
    <>
      <Link className={`${styles.btnGhost} ${styles.btnTall}`} href={lineupsHref(filters, { entry: filters.entry, form: "lineup" })} scroll={false}>
        <Plus />Раскидка
      </Link>
      <Link className={styles.btnPrimary} href={lineupsHref(filters, { entry: filters.entry, form: "script" })} scroll={false}>
        <Plus />Скрипт
      </Link>
    </>
  );
}
