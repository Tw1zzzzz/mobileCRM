import Form from "next/form";
import Link from "next/link";
import { LINEUP_MAPS, LINEUP_TYPES, type LineupFilter, type StaffLineupsPage } from "@/lib/staff/lineups";
import { MAP_NAMES, TYPE_NAMES, lineupsHref } from "./meta";
import { SearchInput } from "./SearchInput";
import styles from "./lineups.module.css";

function Chip({ href, on, children }: { href: string; on: boolean; children: React.ReactNode }) {
  return (
    <Link className={on ? `${styles.chip} ${styles.chipOn}` : styles.chip} href={href} scroll={false} aria-current={on ? "true" : undefined}>
      {children}
    </Link>
  );
}

function SearchForm({ filters }: { filters: LineupFilter }) {
  return (
    <Form action="/staff/lineups" className={styles.search}>
      <svg className={styles.searchIcon} width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#8A97B8" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
        <circle cx="11" cy="11" r="7" />
        <path d="M20 20l-3.5-3.5" />
      </svg>
      <SearchInput defaultValue={filters.q} className={styles.searchInput} />
      {filters.map !== "all" && <input type="hidden" name="map" value={filters.map} />}
      {filters.type !== "all" && <input type="hidden" name="type" value={filters.type} />}
      {filters.side !== "all" && <input type="hidden" name="side" value={filters.side} />}
      {filters.favorites && <input type="hidden" name="fav" value="1" />}
    </Form>
  );
}

/** Search, map chips with their counts, type chips, the side switch and «★ Избранное». All of it lives in the URL. */
export function FilterPanel({ page }: { page: StaffLineupsPage }) {
  const { filters, mapCounts } = page;
  return (
    <div className={styles.filters}>
      <SearchForm filters={filters} />
      <div role="group" aria-label="Карта" className={styles.chipRow}>
        <Chip href={lineupsHref(filters, { map: "all" })} on={filters.map === "all"}>
          Все<span className={styles.chipCount}>{mapCounts.all}</span>
        </Chip>
        {LINEUP_MAPS.map((map) => (
          <Chip key={map} href={lineupsHref(filters, { map })} on={filters.map === map}>
            {MAP_NAMES[map]}<span className={styles.chipCount}>{mapCounts[map]}</span>
          </Chip>
        ))}
      </div>
      <div className={styles.typeRow}>
        <div role="group" aria-label="Тип" className={`${styles.chipRow} ${styles.typeChips}`}>
          <Chip href={lineupsHref(filters, { type: "all" })} on={filters.type === "all"}>Все типы</Chip>
          {LINEUP_TYPES.map((type) => (
            <Chip key={type} href={lineupsHref(filters, { type })} on={filters.type === type}>{TYPE_NAMES[type]}</Chip>
          ))}
        </div>
        <div role="group" aria-label="Сторона" className={styles.seg}>
          {(["all", "t", "ct"] as const).map((side) => (
            <Link
              key={side}
              className={filters.side === side ? styles.segOn : undefined}
              href={lineupsHref(filters, { side })}
              scroll={false}
              aria-current={filters.side === side ? "true" : undefined}
            >
              {side === "all" ? "Обе" : side.toUpperCase()}
            </Link>
          ))}
        </div>
        <Chip href={lineupsHref(filters, { favorites: !filters.favorites })} on={filters.favorites}>★ Избранное</Chip>
      </div>
    </div>
  );
}
