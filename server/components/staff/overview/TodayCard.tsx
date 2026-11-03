import Link from "next/link";
import type { TodayEvent } from "@/lib/staff/overview-today";
import styles from "./overview.module.css";

const CALENDAR_PATH = "/staff/calendar";

/** «#командный», «#vexa», «#личное»: the board's hash-tag under the second event. */
function tagOf(event: TodayEvent): string {
  if (event.tag === "team") return "#командный";
  if (event.tag === "personal") return "#личное";
  return `#${event.playerName ?? "игрок"}`;
}

/** The header card of the board: today's first two calendar events, linking to the calendar. */
export function TodayCard({ events }: { events: readonly TodayEvent[] }) {
  const [first, second] = events;
  const more = events.length - 2;
  return (
    <Link href={CALENDAR_PATH} className={styles.today}>
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#3FD0FF" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <rect x="3" y="5" width="18" height="16" rx="2" />
        <path d="M3 10h18M8 3v4M16 3v4" />
      </svg>
      <span className={styles.todayText}>
        {first ? (
          <>
            <span className={styles.todayTitle}>
              {first.title} <span className={styles.todayTime}>{first.time}</span>
            </span>
            <span className={styles.todaySub}>
              {second ? (
                <>
                  {second.title} <span className={styles.todaySubTime}>{second.time}</span> · {tagOf(second)}
                  {more > 0 ? ` · ещё ${more}` : ""}
                </>
              ) : (
                tagOf(first)
              )}
            </span>
          </>
        ) : (
          <>
            <span className={styles.todayTitle}>Сегодня событий нет</span>
            <span className={styles.todaySub}>Открыть календарь</span>
          </>
        )}
      </span>
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#A3AECB" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M7 17L17 7M8 7h9v9" />
      </svg>
    </Link>
  );
}
