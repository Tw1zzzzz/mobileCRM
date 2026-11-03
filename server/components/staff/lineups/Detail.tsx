import Link from "next/link";
import { deleteStaffLineupAction, toggleStaffLineupFavoriteAction } from "@/app/staff/actions";
import { lineupWhen } from "@/lib/staff/lineups-format";
import type { LineupFilter, StaffLineup } from "@/lib/staff/lineups";
import type { Day } from "@/lib/staff/metrics";
import { DetailActions } from "./DetailActions";
import { FilterFields } from "./FilterFields";
import { MAP_NAMES, SIDE_NAMES, TYPE_NAMES, lineupsHref } from "./meta";
import { RevealOnSelect } from "./RevealOnSelect";
import { Tile } from "./Tile";
import styles from "./lineups.module.css";

const STEPS_TITLE = { script: "Шаги скрипта", note: "Текст заметки" } as const;

function returnFields(filters: LineupFilter): Record<string, string> {
  const fields: Record<string, string> = { q: filters.q, map: filters.map, type: filters.type, side: filters.side };
  if (filters.favorites) fields.fav = "1";
  return fields;
}

/** The sticky panel: the entry's tile, chips, video, numbered steps, byline and actions. */
export function Detail({ entry, filters, today }: { entry: StaffLineup; filters: LineupFilter; today: Day }) {
  const steps = entry.details.split("\n").map((line) => line.trim()).filter(Boolean);
  const stepsTitle = entry.type === "script" || entry.type === "note" ? STEPS_TITLE[entry.type] : "Как бросать";
  return (
    <section className={styles.detail}>
      <RevealOnSelect entryId={entry.id} listSelector="[data-lineups-list]" />
      <Tile entry={entry} large />
      <div className={styles.detailBody}>
        <div className={styles.tags}>
          <span className={`${styles.st} ${styles.stOk}`}>{TYPE_NAMES[entry.type]}</span>
          <span className={`${styles.st} ${styles.stMute}`}>{MAP_NAMES[entry.map]}</span>
          <span className={`${styles.st} ${styles.stMute}`}>{SIDE_NAMES[entry.side]}</span>
        </div>
        <h2 className={styles.detailTitle}>{entry.title}</h2>
        {entry.videoUrl ? (
          <a className={styles.btnPrimary} href={entry.videoUrl} target="_blank" rel="noopener noreferrer">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7 5l12 7-12 7z" /></svg>
            Смотреть видео
          </a>
        ) : (
          <span className={styles.noVideo}>Видео не добавлено</span>
        )}
        <div className={styles.stepsBlock}>
          <span className={styles.eyebrow}>{stepsTitle}</span>
          {steps.length > 0 ? (
            steps.map((step, index) => (
              <div className={styles.step} key={`${index}-${step}`}>
                <b>{index + 1}</b>
                <span>{step}</span>
              </div>
            ))
          ) : (
            <span className={styles.noVideo}>Подробности пока не добавлены</span>
          )}
        </div>
        <span className={styles.byline}>Добавил {entry.author} · {lineupWhen(entry.createdAt, today)} · видно всем игрокам команды</span>
        <div className={styles.rule} />
        <DetailActions title={entry.title} id={entry.id} deleteAction={deleteStaffLineupAction} returnTo={returnFields(filters)}>
          <form action={toggleStaffLineupFavoriteAction}>
            <input type="hidden" name="id" value={entry.id} />
            <input type="hidden" name="selected" value={entry.id} />
            <FilterFields filters={filters} />
            <button className={styles.btnGhost} type="submit" aria-pressed={entry.favorite}>
              {entry.favorite ? "★ В избранном" : "☆ В избранное"}
            </button>
          </form>
          <Link className={styles.btnGhost} href={lineupsHref(filters, { entry: entry.id, form: "edit" })} scroll={false}>Редактировать</Link>
        </DetailActions>
      </div>
    </section>
  );
}
