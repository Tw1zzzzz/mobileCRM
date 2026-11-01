import Link from "next/link";
import { deleteCalendarEventAction, saveCalendarEventAction } from "@/app/staff/actions";
import { calendarDateTimeInput, calendarDayKey, calendarInputForDay, CALENDAR_ERROR_TEXT, type CalendarEventRow, type CalendarFilter, type CalendarPageData, type CalendarTag } from "@/lib/staff/calendar";
import { STAFF_TIME_ZONE } from "@/lib/staff/metrics";
import { ConfirmDeleteButton } from "./ConfirmDeleteButton";
import styles from "./calendar.module.css";

const WEEKDAYS = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"];
const TAGS: { id: CalendarTag; title: string }[] = [
  { id: "personal", title: "Личное" }, { id: "team", title: "Команда" }, { id: "player", title: "Игрок" },
];

function monthLabel(year: number, month: number): string {
  return new Intl.DateTimeFormat("ru-RU", { month: "long", year: "numeric", timeZone: "UTC" }).format(new Date(Date.UTC(year, month - 1, 1)));
}
function shiftMonth(year: number, month: number, by: number): string {
  const date = new Date(Date.UTC(year, month - 1 + by, 1));
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}`;
}
function labelDay(day: string): string {
  return new Intl.DateTimeFormat("ru-RU", { day: "numeric", month: "long", weekday: "long", timeZone: "UTC" }).format(new Date(`${day}T12:00:00Z`));
}
function time(date: Date): string {
  return new Intl.DateTimeFormat("ru-RU", { timeZone: STAFF_TIME_ZONE, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(date);
}
function eventDay(event: CalendarEventRow): string { return calendarDayKey(event.startsAt); }
function filterEvents(events: CalendarEventRow[], filter: CalendarFilter): CalendarEventRow[] {
  if (filter === "all") return events;
  if (filter.startsWith("player:")) return events.filter((event) => event.tag === "player" && event.playerId === filter.slice(7));
  return events.filter((event) => event.tag === filter);
}
function href(month: string, day: string, tag: CalendarFilter, openCreate = false): string {
  const query = new URLSearchParams({ month, day });
  if (tag !== "all") query.set("tag", tag);
  if (openCreate) query.set("new", "1");
  return `/staff/calendar?${query.toString()}${openCreate ? "#new-event" : "#calendar"}`;
}
function tagClass(tag: CalendarTag): string {
  return tag === "team" ? styles.team : tag === "player" ? styles.player : styles.personal;
}
function tagTitle(tag: CalendarTag): string { return TAGS.find((value) => value.id === tag)?.title ?? "Событие"; }
function dateInput(value: Date): string { return calendarDateTimeInput(value); }
function safeLinks(links: string[]): string[] {
  return links.filter((value) => {
    try { const url = new URL(value); return (url.protocol === "http:" || url.protocol === "https:") && !url.username && !url.password; }
    catch { return false; }
  });
}

function EventFields({ event, page, day, create = false }: { event?: CalendarEventRow; page: CalendarPageData; day: string; create?: boolean }) {
  const start = event ? dateInput(event.startsAt) : calendarInputForDay(day, 17);
  const end = event ? dateInput(event.endsAt) : calendarInputForDay(day, 18);
  return (
    <form action={saveCalendarEventAction} className={styles.form}>
      {event && <input type="hidden" name="id" value={event.id} />}
      <label className={styles.field}>Название<input name="title" required maxLength={120} defaultValue={event?.title ?? ""} placeholder="Тренировка команды" /></label>
      <div className={styles.formRow}>
        <label className={styles.field}>Тип<select name="tag" defaultValue={event?.tag ?? "team"}>{TAGS.map((tag) => <option key={tag.id} value={tag.id}>{tag.title}</option>)}</select></label>
        <label className={styles.field}>Игрок<select name="playerId" defaultValue={event?.playerId ?? ""}><option value="">Не выбран</option>{page.players.map((player) => <option key={player.id} value={player.id}>{player.nickname}</option>)}</select></label>
      </div>
      <div className={styles.formRow}>
        <label className={styles.field}>Начало<input type="datetime-local" name="startsAt" required defaultValue={start} /></label>
        <label className={styles.field}>Окончание<input type="datetime-local" name="endsAt" required defaultValue={end} /></label>
      </div>
      <label className={styles.field}>Описание<textarea name="details" maxLength={4000} defaultValue={event?.details ?? ""} rows={3} /></label>
      <label className={styles.field}>Ссылки<textarea name="links" defaultValue={event?.links.join("\n") ?? ""} placeholder="https://…" rows={2} /></label>
      <div className={styles.formActions}>
        <button className={styles.primary} type="submit">{create ? "Добавить событие" : "Сохранить"}</button>
        {event && <ConfirmDeleteButton action={deleteCalendarEventAction} />}
      </div>
      <p className={styles.formHint}>Личные события видны только вам. События команды и игрока сохраняются в общем календаре.</p>
    </form>
  );
}

function EventCard({ event, page }: { event: CalendarEventRow; page: CalendarPageData }) {
  return (
    <article className={`${styles.eventCard} ${tagClass(event.tag)}`}>
      <div className={styles.eventHeading}>
        <span className={styles.eventTitle}>{event.title}</span>
        <span className={styles.eventTime}>{time(event.startsAt)}–{time(event.endsAt)}</span>
      </div>
      <span className={styles.eventTag}>{event.tag === "player" && event.playerName ? `${tagTitle(event.tag)} · ${event.playerName}` : tagTitle(event.tag)}</span>
      {event.details && <p className={styles.eventDetails}>{event.details}</p>}
      {safeLinks(event.links).length > 0 && <div className={styles.eventLinks}>{safeLinks(event.links).map((link) => <a key={link} href={link} target="_blank" rel="noreferrer">Открыть ссылку ↗</a>)}</div>}
      <details className={styles.edit}>
        <summary>Редактировать</summary>
        <EventFields event={event} page={page} day={page.selectedDay} />
      </details>
    </article>
  );
}

export function CalendarView({ page }: { page: CalendarPageData }) {
  const filtered = filterEvents(page.events, page.filter);
  const days = new Date(Date.UTC(page.year, page.month, 0)).getUTCDate();
  const first = new Date(Date.UTC(page.year, page.month - 1, 1));
  const leading = (first.getUTCDay() + 6) % 7;
  const cellCount = Math.ceil((leading + days) / 7) * 7;
  const cells = Array.from({ length: cellCount }, (_, index) => new Date(Date.UTC(page.year, page.month - 1, index - leading + 1)));
  const selectedEvents = filtered.filter((event) => eventDay(event) === page.selectedDay);
  const counts = Object.fromEntries(TAGS.map((tag) => [tag.id, page.events.filter((event) => event.tag === tag.id).length]));
  const today = calendarDayKey(new Date());
  const selectedLabel = labelDay(page.selectedDay);
  const newHref = href(`${page.year}-${String(page.month).padStart(2, "0")}`, page.selectedDay, page.filter, true);

  return (
    <div className={styles.page}>
      <div className={styles.toolbar}>
        <div>
          <p className={styles.eyebrow}>{page.events.length} событий в этом месяце</p>
          <h2 className={styles.title}>{monthLabel(page.year, page.month)}</h2>
        </div>
        <Link className={styles.primary} href={newHref}>＋ Создать событие</Link>
      </div>
      <nav className={styles.filters} aria-label="Фильтр событий">
        <Link className={`${styles.chip} ${page.filter === "all" ? styles.chipOn : ""}`} href={href(`${page.year}-${String(page.month).padStart(2, "0")}`, page.selectedDay, "all")}>Все <span>{page.events.length}</span></Link>
        {TAGS.map((tag) => <Link key={tag.id} className={`${styles.chip} ${tagClass(tag.id)} ${page.filter === tag.id ? styles.chipOn : ""}`} href={href(`${page.year}-${String(page.month).padStart(2, "0")}`, page.selectedDay, tag.id)}>{tag.title}<span>{counts[tag.id]}</span></Link>)}
        {page.players.map((player) => <Link key={player.id} className={`${styles.chip} ${styles.player} ${page.filter === `player:${player.id}` ? styles.chipOn : ""}`} href={href(`${page.year}-${String(page.month).padStart(2, "0")}`, page.selectedDay, `player:${player.id}`)}>{player.nickname}</Link>)}
      </nav>
      {page.error && <p className={styles.error} role="alert">{CALENDAR_ERROR_TEXT[page.error]}</p>}
      <div className={styles.layout} id="calendar">
        <section className={styles.monthCard} aria-label={`Календарь на ${monthLabel(page.year, page.month)}`}>
          <div className={styles.monthHeader}>
            <Link className={styles.iconButton} aria-label="Предыдущий месяц" href={href(shiftMonth(page.year, page.month, -1), `${shiftMonth(page.year, page.month, -1)}-01`, page.filter)}>‹</Link>
            <strong>{monthLabel(page.year, page.month)}</strong>
            <Link className={styles.ghostButton} href={href(`${today.slice(0, 7)}`, today, page.filter)}>Сегодня</Link>
            <Link className={styles.iconButton} aria-label="Следующий месяц" href={href(shiftMonth(page.year, page.month, 1), `${shiftMonth(page.year, page.month, 1)}-01`, page.filter)}>›</Link>
          </div>
          <div className={styles.weekdays}>{WEEKDAYS.map((day) => <span key={day}>{day}</span>)}</div>
          <div className={styles.grid}>
            {cells.map((date) => {
              const key = calendarDayKey(date);
              const entries = filtered.filter((event) => eventDay(event) === key);
              const outside = date.getUTCMonth() !== page.month - 1;
              return <Link key={key} className={`${styles.day} ${outside ? styles.outside : ""} ${key === page.selectedDay ? styles.selected : ""} ${key === today ? styles.today : ""}`} href={href(`${page.year}-${String(page.month).padStart(2, "0")}`, key, page.filter)} aria-label={`${labelDay(key)}${entries.length ? `, событий: ${entries.length}` : ", событий нет"}`} aria-current={key === page.selectedDay ? "date" : undefined}>
                <span className={styles.dayNumber}>{String(date.getUTCDate()).padStart(2, "0")}</span>
                <span className={styles.previews}>{entries.slice(0, 2).map((event) => <span key={event.id} className={`${styles.preview} ${tagClass(event.tag)}`}>{time(event.startsAt)} {event.title}</span>)}{entries.length > 2 && <span className={styles.more}>+{entries.length - 2} ещё</span>}</span>
                {entries.length > 0 && <span className={styles.eventDots} aria-hidden="true">{entries.slice(0, 3).map((event) => <i key={event.id} className={tagClass(event.tag)} />)}</span>}
              </Link>;
            })}
          </div>
        </section>
        <aside className={styles.dayPanel}>
          <section className={styles.dayCard}>
            <div className={styles.dayHeading}>
              <div><p className={styles.eyebrow}>{selectedEvents.length ? `${selectedEvents.length} события` : "Свободный день"}</p><h3>{selectedLabel}</h3></div>
              <Link className={styles.addButton} href={newHref} aria-label="Добавить событие">＋</Link>
            </div>
            {selectedEvents.length ? <div className={styles.events}>{selectedEvents.map((event) => <EventCard key={event.id} event={event} page={page} />)}</div> : <div className={styles.empty}><span aria-hidden="true">▦</span><strong>Событий нет</strong><span>Выберите другую дату или добавьте событие</span></div>}
            <details id="new-event" className={styles.newEvent} open={page.openCreate}>
              <summary>＋ Событие на эту дату</summary>
              <EventFields page={page} day={page.selectedDay} create />
            </details>
          </section>
          <p className={styles.note}>События команды синхронизируются с приложением. Напоминания ставит телефон участника.</p>
        </aside>
      </div>
    </div>
  );
}
