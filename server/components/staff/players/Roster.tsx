import type { ReadinessStatus } from "@/lib/staff/metrics";
import { NO_DATA } from "@/lib/staff/overview-format";
import { EMPTY_CELL, type PlayerRow, type RosterData } from "@/lib/staff/players";
import styles from "./players.module.css";

const RING_RADIUS = 27;
const RING_LENGTH = 2 * Math.PI * RING_RADIUS;

const STATUS: Record<ReadinessStatus, { label: string; pill: string; color: string }> = {
  ready: { label: "Готов", pill: styles.stOk, color: "#3fd0ff" },
  doubt: { label: "Под вопросом", pill: styles.stMid, color: "#ffd27a" },
  risk: { label: "Риск", pill: styles.stBad, color: "#ff8a3d" },
};

export const LEGEND =
  "Готовность 0–100 — из сна, настроения, энергии и когнитивного индекса за последние сутки. " +
  "Готов · 70+, под вопросом · 55–69, риск · ниже 55";

function summaryText({ ready, doubt, risk, noData }: RosterData["summary"]): string {
  const base = `Готовы ${ready} · под вопросом ${doubt} · риск ${risk}`;
  return noData > 0 ? `${base} · нет данных ${noData}` : base;
}

function Ring({ value, status }: { value: number | null; status: ReadinessStatus | null }) {
  const arc = value === null ? 0 : (value / 100) * RING_LENGTH;
  return (
    <span className={styles.ring} aria-hidden="true">
      <svg width="64" height="64" viewBox="0 0 64 64">
        <circle cx="32" cy="32" r={RING_RADIUS} fill="none" stroke="#1e2740" strokeWidth="6" />
        {status !== null && (
          <circle
            cx="32"
            cy="32"
            r={RING_RADIUS}
            fill="none"
            stroke={STATUS[status].color}
            strokeWidth="6"
            strokeLinecap="round"
            strokeDasharray={`${arc.toFixed(1)} ${RING_LENGTH.toFixed(1)}`}
            transform="rotate(-90 32 32)"
          />
        )}
      </svg>
      <span className={styles.ringValue}>{value ?? EMPTY_CELL}</span>
    </span>
  );
}

function PlayerCard({ row }: { row: PlayerRow }) {
  const status = row.status === null ? null : STATUS[row.status];
  return (
    <li className={styles.card}>
      <div className={styles.cardTop}>
        <Ring value={row.readiness} status={row.status} />
        <span className={styles.who}>
          <span className={styles.nick}>{row.nickname}</span>
          <span className={`${styles.st} ${status?.pill ?? styles.stNone}`}>{status?.label ?? NO_DATA}</span>
        </span>
      </div>
      <div className={styles.mini}>
        <span>
          Сон<b>{row.sleep}</b>
        </span>
        <span>
          Настр.<b>{row.mood}</b>
        </span>
        <span>
          Когн.<b>{row.cog}</b>
        </span>
      </div>
      <span className={styles.last}>Активность: {row.lastActivity}</span>
    </li>
  );
}

/** «Игроки»: the segments, today's readiness of each player and the legend. Cards are not links (plan B). */
export function Roster({ roster }: { roster: RosterData }) {
  const { rows, summary } = roster;
  return (
    <>
      <div className={styles.segments} role="group" aria-label="Раздел игроков">
        <span className={`${styles.tab} ${styles.tabOn}`} aria-current="page">
          Состав и карточки
        </span>
        <span className={`${styles.tab} ${styles.tabSoon}`} aria-disabled="true">
          Питание команды
          <em className={styles.soon}>Скоро</em>
        </span>
      </div>
      <section className={styles.section} aria-labelledby="ready-today">
        <div className={styles.sectionHead}>
          <h2 id="ready-today" className={styles.sectionTitle}>
            Готовность сегодня
          </h2>
          {rows.length > 0 && <span className={styles.sub}>{summaryText(summary)}</span>}
        </div>
        {rows.length === 0 ? (
          <p className={styles.empty}>В команде пока нет игроков</p>
        ) : (
          <ul className={styles.cards} aria-label="Игроки команды">
            {rows.map((row) => (
              <PlayerCard key={row.id} row={row} />
            ))}
          </ul>
        )}
        <span className={styles.legend}>{LEGEND}</span>
      </section>
    </>
  );
}
