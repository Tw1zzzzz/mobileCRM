import Link from "next/link";
import { saveStaffLineupAction } from "@/app/staff/actions";
import {
  LINEUP_MAPS,
  LINEUP_TYPES,
  type LineupError,
  type LineupFilter,
  type LineupFormMode,
  type StaffLineup,
} from "@/lib/staff/lineups";
import { Dialog } from "./Dialog";
import { FilterFields } from "./FilterFields";
import { ERROR_TEXT, MAP_NAMES, TYPE_NAMES, lineupsHref } from "./meta";
import styles from "./lineups.module.css";

interface LineupFormProps {
  mode: LineupFormMode;
  filters: LineupFilter;
  /** The entry being edited; null for a new one. */
  entry: StaffLineup | null;
  error: LineupError | null;
}

function Pick({ name, value, label, checked }: { name: string; value: string; label: string; checked: boolean }) {
  return (
    <label className={styles.pick}>
      <input type="radio" name={name} value={value} defaultChecked={checked} />
      <span className={styles.chip}>{label}</span>
    </label>
  );
}

/** The create / edit dialog of the board. The server action and its validation are the existing ones. */
export function LineupForm({ mode, filters, entry, error }: LineupFormProps) {
  const type = entry?.type ?? (mode === "script" ? "script" : "smoke");
  const map = entry?.map ?? (filters.map !== "all" ? filters.map : "mirage");
  const side = entry?.side ?? "t";
  const closeHref = lineupsHref(filters, { entry: filters.entry });
  const heading = entry ? "Редактирование" : type === "script" ? "Новый скрипт" : "Новая раскидка";
  return (
    <Dialog titleId="lineup-form-title" closeHref={closeHref}>
      <div className={styles.dlgHead}>
        <h2 id="lineup-form-title">{heading}</h2>
        <Link className={styles.iconBtn} href={closeHref} scroll={false} aria-label="Закрыть">✕</Link>
      </div>
      <form action={saveStaffLineupAction} className={styles.dlgForm} key={entry?.id ?? mode}>
        {entry && <input type="hidden" name="id" value={entry.id} />}
        <input type="hidden" name="return_form" value={mode} />
        {entry && <FilterFields filters={filters} prefix="return_" />}
        <div className={styles.fld}>
          <label htmlFor="lineup-title">Название *</label>
          <input
            id="lineup-title"
            className={styles.inp}
            name="title"
            required
            maxLength={120}
            autoFocus
            defaultValue={entry?.title ?? ""}
            placeholder={type === "script" ? "Например, Дефолт B-сайт" : "Например, Смок в окно"}
          />
        </div>
        <div className={styles.fld} role="radiogroup" aria-label="Тип">
          <span className={styles.lbl}>Тип</span>
          <div className={styles.pickRow}>
            {LINEUP_TYPES.map((value) => <Pick key={value} name="type" value={value} label={TYPE_NAMES[value]} checked={value === type} />)}
          </div>
        </div>
        <div className={styles.fld} role="radiogroup" aria-label="Карта">
          <span className={styles.lbl}>Карта</span>
          <div className={styles.pickRow}>
            {LINEUP_MAPS.map((value) => <Pick key={value} name="map" value={value} label={MAP_NAMES[value]} checked={value === map} />)}
          </div>
        </div>
        <div className={styles.fld} role="radiogroup" aria-label="Сторона">
          <span className={styles.lbl}>Сторона</span>
          <div className={`${styles.seg} ${styles.segForm}`}>
            {(["t", "ct"] as const).map((value) => (
              <label key={value} className={styles.segPick}>
                <input type="radio" name="side" value={value} defaultChecked={value === side} />
                <span>{value.toUpperCase()}</span>
              </label>
            ))}
          </div>
        </div>
        <div className={styles.fld}>
          <label htmlFor="lineup-details">
            <span className={styles.onlyLineup}>Описание: откуда и как бросать</span>
            <span className={styles.onlyScript}>Шаги скрипта и роли</span>
          </label>
          <textarea
            id="lineup-details"
            className={`${styles.inp} ${styles.area}`}
            name="details"
            rows={4}
            maxLength={4000}
            defaultValue={entry?.details ?? ""}
            placeholder={type === "script" ? "Kairo — флешка в банан, первый выход" : "Встать у ящика, прицел на антенну, jumpthrow"}
          />
          <span className={styles.hint}>Каждый шаг — с новой строки</span>
        </div>
        <div className={styles.fld}>
          <label htmlFor="lineup-video">Ссылка на видео</label>
          <input id="lineup-video" className={styles.inp} name="videoUrl" type="url" maxLength={2048} placeholder="https://" defaultValue={entry?.videoUrl ?? ""} />
        </div>
        {error && <span className={styles.err} role="alert">{ERROR_TEXT[error]}</span>}
        <button className={`${styles.btnPrimary} ${styles.btnSave}`} type="submit">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
          Сохранить
        </button>
      </form>
    </Dialog>
  );
}
