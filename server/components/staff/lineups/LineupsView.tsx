import Link from "next/link";
import {
  deleteStaffLineupAction,
  saveStaffLineupAction,
  toggleStaffLineupFavoriteAction,
} from "@/app/staff/actions";
import { ConfirmDeleteButton } from "@/components/staff/calendar/ConfirmDeleteButton";
import {
  LINEUP_MAPS,
  LINEUP_TYPES,
  type LineupFilter,
  type LineupMap,
  type StaffLineup,
  type StaffLineupType,
  type StaffLineupsPage,
} from "@/lib/staff/lineups";
import styles from "./lineups.module.css";

const MAP_NAMES: Record<LineupMap, string> = {
  mirage: "Mirage", inferno: "Inferno", nuke: "Nuke", ancient: "Ancient", anubis: "Anubis", dust2: "Dust II", train: "Train",
};
const TYPE_NAMES: Record<StaffLineupType, string> = {
  smoke: "Смок", flash: "Флешка", molotov: "Молотов", grenade: "HE", script: "Скрипт",
};
const TYPE_SYMBOLS: Record<StaffLineupType, string> = { smoke: "◌", flash: "✳", molotov: "♨", grenade: "◉", script: "☷" };

function href(filters: LineupFilter, change: Partial<LineupFilter> = {}): string {
  const next = { ...filters, ...change };
  const query = new URLSearchParams();
  if (next.q) query.set("q", next.q);
  if (next.map !== "all") query.set("map", next.map);
  if (next.type !== "all") query.set("type", next.type);
  if (next.side !== "all") query.set("side", next.side);
  if (next.favorites) query.set("fav", "1");
  if (next.entry) query.set("entry", next.entry);
  const value = query.toString();
  return `/staff/lineups${value ? `?${value}` : ""}`;
}

function typeClass(type: StaffLineupType): string {
  return styles[`type_${type}` as keyof typeof styles] as string;
}

function formatDate(value: Date): string {
  return new Intl.DateTimeFormat("ru-RU", { day: "numeric", month: "short", year: "numeric" }).format(value);
}

function FilterLink({ selected, href: url, children }: { selected: boolean; href: string; children: React.ReactNode }) {
  return <Link className={`${styles.chip} ${selected ? styles.chipOn : ""}`} href={url} aria-current={selected ? "true" : undefined}>{children}</Link>;
}

function LineupForm({ entry, filters }: { entry?: StaffLineup; filters: LineupFilter }) {
  return (
    <form action={saveStaffLineupAction} className={styles.form}>
      {entry && <input type="hidden" name="id" value={entry.id} />}
      {(["q", "map", "type", "side"] as const).map((name) => <input key={name} type="hidden" name={`return_${name}`} value={filters[name]} />)}
      {filters.favorites && <input type="hidden" name="return_fav" value="1" />}
      <label className={styles.field}>Название<input name="title" required maxLength={120} defaultValue={entry?.title ?? ""} placeholder="Например, Смок в окно с T-спавна" /></label>
      <div className={styles.formRow}>
        <label className={styles.field}>Карта<select name="map" defaultValue={entry?.map ?? "mirage"}>{LINEUP_MAPS.map((map) => <option key={map} value={map}>{MAP_NAMES[map]}</option>)}</select></label>
        <label className={styles.field}>Тип<select name="type" defaultValue={entry?.type ?? "smoke"}>{LINEUP_TYPES.map((type) => <option key={type} value={type}>{TYPE_NAMES[type]}</option>)}</select></label>
      </div>
      <label className={styles.field}>Сторона<select name="side" defaultValue={entry?.side ?? "t"}><option value="t">Атака · T</option><option value="ct">Защита · CT</option></select></label>
      <label className={styles.field}>Как выполнить<textarea name="details" rows={5} maxLength={4000} defaultValue={entry?.details ?? ""} placeholder="Позиция, прицел, тайминг и последовательность действий" /></label>
      <label className={styles.field}>Видео или демо-ссылка<input name="videoUrl" type="url" maxLength={2048} defaultValue={entry?.videoUrl ?? ""} placeholder="https://…" /></label>
      <div className={styles.formActions}>
        <button className={styles.primary} type="submit">{entry ? "Сохранить изменения" : "Добавить в базу"}</button>
        {entry && <span className={styles.formHint}>Запись будет видна команде.</span>}
      </div>
    </form>
  );
}

function EntryCard({ entry, selected, filters }: { entry: StaffLineup; selected: boolean; filters: LineupFilter }) {
  return (
    <Link className={`${styles.entry} ${selected ? styles.entrySelected : ""}`} href={href(filters, { entry: entry.id })} aria-current={selected ? "true" : undefined}>
      <span className={`${styles.typeIcon} ${typeClass(entry.type)}`} aria-hidden="true">{TYPE_SYMBOLS[entry.type]}</span>
      <span className={styles.entryText}>
        <span className={styles.entryTitle}>{entry.title}</span>
        <span className={styles.entryMeta}>{MAP_NAMES[entry.map]} <i>·</i> {entry.side.toUpperCase()} <i>·</i> {TYPE_NAMES[entry.type]}</span>
      </span>
      <span className={styles.entryTools}>
        {entry.favorite && <span className={styles.star} aria-label="В избранном">★</span>}
        {entry.videoUrl && <span className={styles.videoMark} aria-label="Есть видео">▶</span>}
      </span>
    </Link>
  );
}

function Detail({ entry, filters }: { entry: StaffLineup; filters: LineupFilter }) {
  const steps = entry.details.split("\n").map((value) => value.trim()).filter(Boolean);
  const type = TYPE_NAMES[entry.type];
  return (
    <article className={styles.detail}>
      <div className={styles.detailTop}>
        <span className={`${styles.typeIcon} ${styles.detailIcon} ${typeClass(entry.type)}`} aria-hidden="true">{TYPE_SYMBOLS[entry.type]}</span>
        <div className={styles.detailHeading}>
          <p className={styles.eyebrow}>{MAP_NAMES[entry.map]} <span>·</span> {entry.side.toUpperCase()} <span>·</span> {type}</p>
          <h2>{entry.title}</h2>
          <p className={styles.byline}>Добавил {entry.author} · {formatDate(entry.createdAt)} · видно команде</p>
        </div>
        <form action={toggleStaffLineupFavoriteAction}>
          <input type="hidden" name="id" value={entry.id} />
          {(["q", "map", "type", "side"] as const).map((name) => <input key={name} type="hidden" name={name} value={filters[name]} />)}
          {filters.favorites && <input type="hidden" name="fav" value="1" />}
          <button className={`${styles.starButton} ${entry.favorite ? styles.starButtonOn : ""}`} type="submit" aria-label={entry.favorite ? "Убрать из избранного" : "Добавить в избранное"}>{entry.favorite ? "★" : "☆"}</button>
        </form>
      </div>
      <section className={styles.steps}>
        <div className={styles.sectionTitle}><span className={styles.sectionNumber}>01</span><div><h3>{entry.type === "script" ? "Шаги скрипта" : "Как бросать"}</h3><p>Порядок действий для раунда</p></div></div>
        {steps.length ? <ol>{steps.map((step, index) => <li key={`${index}-${step}`}><span>{String(index + 1).padStart(2, "0")}</span><p>{step}</p></li>)}</ol> : <p className={styles.emptyDetails}>Подробности пока не добавлены.</p>}
      </section>
      {entry.videoUrl && <a className={styles.videoLink} href={entry.videoUrl} target="_blank" rel="noreferrer"><span>▶</span><span><strong>Открыть видео</strong><small>Перейти к демонстрации раскидки</small></span><span aria-hidden="true">↗</span></a>}
      <details className={styles.editDetails}>
        <summary>Редактировать запись</summary>
        <LineupForm entry={entry} filters={filters} />
      </details>
      <form action={deleteStaffLineupAction} className={styles.deleteForm}>
        <input type="hidden" name="id" value={entry.id} />
        {(["q", "map", "type", "side"] as const).map((name) => <input key={name} type="hidden" name={name} value={filters[name]} />)}
        {filters.favorites && <input type="hidden" name="fav" value="1" />}
        <ConfirmDeleteButton action={deleteStaffLineupAction} message="Удалить эту запись?" />
      </form>
    </article>
  );
}

export function LineupsView({ page }: { page: StaffLineupsPage }) {
  const filters = page.filters;
  return (
    <div className={styles.page}>
      <section className={styles.intro}>
        <div><p className={styles.eyebrow}>База команды · CS2</p><h2>Подготовка начинается до раунда</h2><p>Сохраняйте гранаты и командные сценарии, чтобы игроки открывали их в приложении.</p></div>
        <div className={styles.total}><strong>{page.totalForTeam}</strong><span>{page.totalForTeam === 1 ? "запись" : "записей"}<br />в базе команды</span></div>
      </section>
      {page.error && <p className={styles.error} role="alert">{page.error === "invalid" ? "Проверьте название, тип, карту и ссылку на видео." : page.error === "not-found" ? "Запись больше недоступна." : "Не удалось сохранить запись. Обновите страницу и попробуйте снова."}</p>}
      {page.notice && <p className={styles.notice} role="status">{{ created: "Запись добавлена.", saved: "Изменения сохранены.", deleted: "Запись удалена." }[page.notice]}</p>}
      <div className={styles.toolbar}>
        <form action="/staff/lineups" method="get" className={styles.searchForm}>
          <span aria-hidden="true">⌕</span><input type="search" name="q" defaultValue={filters.q} placeholder="Найти раскидку или скрипт" aria-label="Поиск по базе" />
          {filters.map !== "all" && <input type="hidden" name="map" value={filters.map} />}
          {filters.type !== "all" && <input type="hidden" name="type" value={filters.type} />}
          {filters.side !== "all" && <input type="hidden" name="side" value={filters.side} />}
          {filters.favorites && <input type="hidden" name="fav" value="1" />}
          <button type="submit">Найти</button>
        </form>
        <details className={styles.addMenu}>
          <summary>＋ Добавить запись</summary>
          <div className={styles.addPanel}><div><h3>Новая запись</h3><p>Заполните детали, чтобы команда могла повторить розыгрыш.</p></div><LineupForm filters={filters} /></div>
        </details>
      </div>
      <nav className={styles.filters} aria-label="Фильтры раскидок">
        <div className={styles.filterRow}>
          <span className={styles.filterLabel}>Карта</span>
          <FilterLink selected={filters.map === "all"} href={href(filters, { map: "all", entry: null })}>Все карты</FilterLink>
          {LINEUP_MAPS.map((map) => <FilterLink key={map} selected={filters.map === map} href={href(filters, { map, entry: null })}>{MAP_NAMES[map]}</FilterLink>)}
        </div>
        <div className={styles.filterRow}>
          <span className={styles.filterLabel}>Тип</span>
          <FilterLink selected={filters.type === "all"} href={href(filters, { type: "all", entry: null })}>Все типы</FilterLink>
          {LINEUP_TYPES.map((type) => <FilterLink key={type} selected={filters.type === type} href={href(filters, { type, entry: null })}>{TYPE_NAMES[type]}</FilterLink>)}
          <span className={styles.filterDivider} />
          {(["all", "t", "ct"] as const).map((side) => <FilterLink key={side} selected={filters.side === side} href={href(filters, { side, entry: null })}>{side === "all" ? "Обе стороны" : side.toUpperCase()}</FilterLink>)}
          <span className={styles.filterDivider} />
          <FilterLink selected={filters.favorites} href={href(filters, { favorites: !filters.favorites, entry: null })}>☆ Избранное</FilterLink>
        </div>
      </nav>
      <div className={styles.workspace}>
        <section className={styles.listPanel} aria-label="Записи команды">
          <div className={styles.listHeader}><div><h2>База команды</h2><p>{page.entries.length}{page.hasMore ? "+" : ""} {page.entries.length === 1 ? "запись" : "записей"} по фильтру</p></div><span className={styles.listCount}>{page.entries.length}</span></div>
          {page.entries.length ? <div className={styles.entries}>{page.entries.map((entry) => <EntryCard key={entry.id} entry={entry} selected={page.selected?.id === entry.id} filters={filters} />)}{page.hasMore && <p className={styles.limitNote}>Показаны первые 500 записей — уточните фильтр.</p>}</div> : <div className={styles.empty}><span aria-hidden="true">⌁</span><h3>{page.totalForTeam === 0 ? "База пока пустая" : "Ничего не найдено"}</h3><p>{page.totalForTeam === 0 ? "Добавьте первую раскидку — она будет доступна команде." : "Измените фильтры или добавьте новую запись."}</p><Link href="/staff/lineups">Сбросить фильтры</Link></div>}
        </section>
        <section className={styles.detailPanel} aria-label="Подробности записи">
          {page.selected ? <Detail key={page.selected.id} entry={page.selected} filters={filters} /> : <div className={styles.noSelection}><span>⌖</span><h2>Выберите запись</h2><p>Подробности, шаги и видео появятся здесь.</p></div>}
        </section>
      </div>
    </div>
  );
}
