import Link from "next/link";
import { toggleStaffLineupFavoriteAction } from "@/app/staff/actions";
import { lineupWhen } from "@/lib/staff/lineups-format";
import type { LineupFilter, StaffLineup } from "@/lib/staff/lineups";
import type { Day } from "@/lib/staff/metrics";
import { FilterFields } from "./FilterFields";
import { MAP_NAMES, TYPE_NAMES, lineupsHref } from "./meta";
import { Tile } from "./Tile";
import styles from "./lineups.module.css";

interface EntryCardProps {
  entry: StaffLineup;
  selected: boolean;
  /** The entry the page shows in the detail panel: a star toggle keeps it open. */
  selectedId: string | null;
  filters: LineupFilter;
  today: Day;
}

/** One card of the grid: opens the entry, and a star that adds it to the favourites without leaving the list. */
export function EntryCard({ entry, selected, selectedId, filters, today }: EntryCardProps) {
  return (
    <article className={selected ? `${styles.lu} ${styles.luOn}` : styles.lu}>
      <Link
        className={styles.luLink}
        href={lineupsHref(filters, { entry: entry.id })}
        scroll={false}
        aria-current={selected ? "true" : undefined}
      >
        <Tile entry={entry}>{entry.videoUrl && <span className={styles.vid}>▶ Видео</span>}</Tile>
        <span className={styles.luText}>
          <span className={styles.luTitle}>{entry.title}</span>
          <span className={styles.luMeta}>{MAP_NAMES[entry.map]} · {entry.side.toUpperCase()} · {TYPE_NAMES[entry.type]}</span>
          <span className={styles.luAuthor}>{entry.author} · {lineupWhen(entry.createdAt, today)}</span>
        </span>
      </Link>
      <form action={toggleStaffLineupFavoriteAction} className={styles.starForm}>
        <input type="hidden" name="id" value={entry.id} />
        <input type="hidden" name="selected" value={selectedId ?? ""} />
        <FilterFields filters={filters} />
        <button
          className={entry.favorite ? `${styles.star} ${styles.starOn}` : styles.star}
          type="submit"
          aria-label={entry.favorite ? "Убрать из избранного" : "В избранное"}
          aria-pressed={entry.favorite}
        >
          ★
        </button>
      </form>
    </article>
  );
}
