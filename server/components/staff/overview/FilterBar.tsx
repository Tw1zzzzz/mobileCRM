import Link from "next/link";
import { TEAM, overviewHref, toggleShow, type OverviewFilters, type RosterEntry, type ShowKey } from "@/lib/staff/overview";
import styles from "./overview.module.css";

const METRIC_CHIPS: readonly { key: ShowKey; name: string }[] = [
  { key: "state", name: "Состояние" },
  { key: "sleep", name: "Сон" },
  { key: "food", name: "Питание" },
];

function Chip({ href, on, children }: { href: string; on: boolean; children: React.ReactNode }) {
  return (
    <Link href={href} className={on ? `${styles.chip} ${styles.chipOn}` : styles.chip} aria-current={on ? "true" : undefined}>
      {children}
    </Link>
  );
}

/** The metric chips of the board's header row (links: the choice lives in the URL). */
export function MetricChips({ filters }: { filters: OverviewFilters }) {
  return (
    <div role="group" aria-label="Фильтрация метрик" className={styles.chips}>
      {METRIC_CHIPS.map((chip) => (
        <Chip key={chip.key} href={overviewHref(toggleShow(filters, chip.key))} on={filters.show.includes(chip.key)}>
          {chip.name}
        </Chip>
      ))}
    </div>
  );
}

/** «Вся команда» and one chip per player. */
export function WhoChips({ filters, players }: { filters: OverviewFilters; players: readonly RosterEntry[] }) {
  return (
    <div role="group" aria-label="Чьи данные" className={styles.chips}>
      <Chip href={overviewHref({ ...filters, who: TEAM })} on={filters.who === TEAM}>
        Вся команда
      </Chip>
      {players.map((p) => (
        <Chip key={p.id} href={overviewHref({ ...filters, who: p.id })} on={filters.who === p.id}>
          {p.nickname}
        </Chip>
      ))}
    </div>
  );
}
