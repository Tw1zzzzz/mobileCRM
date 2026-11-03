import Link from "next/link";
import type { LineupNotice, StaffLineupsPage } from "@/lib/staff/lineups";
import { foundLabel } from "@/lib/staff/lineups-format";
import { Detail } from "./Detail";
import { EntryCard } from "./EntryCard";
import { FilterPanel } from "./FilterPanel";
import { LineupForm } from "./LineupForm";
import { ERROR_TEXT, MAP_NAMES, PATH } from "./meta";
import { Toast } from "./Toast";
import styles from "./lineups.module.css";

function noticeText(notice: LineupNotice, page: StaffLineupsPage): string {
  if (notice === "created") {
    return page.selected?.type === "script"
      ? "Скрипт добавлен — игроки увидят его в дневнике"
      : "Раскидка добавлена — игроки увидят её в дневнике";
  }
  return notice === "saved" ? "Изменения сохранены" : "Запись удалена";
}

/** «Раскидки и скрипты»: counters, filters, the cards, and the selected entry beside them. */
export function LineupsView({ page }: { page: StaffLineupsPage }) {
  const { filters, stats } = page;
  const counters = [
    ["Всего записей", stats.total],
    ["Раскидок", stats.lineups],
    ["Скриптов", stats.scripts],
    ["В избранном", stats.favorites],
  ] as const;
  const mapName = filters.map === "all" ? null : MAP_NAMES[filters.map];
  const dialogOpen = page.form !== null;
  return (
    <div className={styles.page}>
      <div className={styles.counters}>
        {counters.map(([label, value]) => (
          <div className={styles.kpi} key={label}>
            <span>{label}</span>
            <span>{value}</span>
          </div>
        ))}
      </div>
      {page.error && !dialogOpen && <p className={styles.banner} role="alert">{ERROR_TEXT[page.error]}</p>}
      <div className={styles.split}>
        <div className={styles.list} data-lineups-list="">
          <FilterPanel page={page} />
          <span className={styles.found}>{foundLabel(page.entries.length, mapName, page.hasMore)}</span>
          {page.entries.length > 0 ? (
            <div className={styles.grid}>
              {page.entries.map((entry) => (
                <EntryCard
                  key={entry.id}
                  entry={entry}
                  selected={page.selected?.id === entry.id}
                  selectedId={page.selected?.id ?? null}
                  filters={filters}
                  today={page.today}
                />
              ))}
            </div>
          ) : (
            <div className={styles.empty}>
              Ничего не найдено. Сбросьте фильтры или добавьте запись
              <Link className={styles.btnGhost} href={PATH}>Сбросить фильтры</Link>
            </div>
          )}
          {page.hasMore && <p className={styles.limitNote}>Показаны первые 500 записей — уточните фильтр.</p>}
        </div>
        <aside className={styles.aside} aria-label="Выбранная запись">
          {page.selected ? (
            <Detail key={page.selected.id} entry={page.selected} filters={filters} today={page.today} />
          ) : (
            <div className={`${styles.empty} ${styles.emptyTall}`}>Выберите запись, чтобы посмотреть детали</div>
          )}
        </aside>
      </div>
      {page.form && <LineupForm mode={page.form} filters={filters} entry={page.form === "edit" ? page.selected : null} error={page.error} />}
      {page.notice && <Toast message={noticeText(page.notice, page)} />}
    </div>
  );
}
