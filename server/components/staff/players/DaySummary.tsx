import Link from "next/link";
import type { SummaryRow } from "@/lib/staff/nutrition";
import { NO_DATA } from "@/lib/staff/overview-format";
import nutrition from "./nutrition.module.css";

const TONE = { ok: nutrition.stOk, mid: nutrition.stMid, bad: nutrition.stBad } as const;

export const NORM_NOTE = "Норма — из настроек игрока (по умолчанию 2\u00a0400 ккал)";

/** «Сводка за день»: today's meals per player against the player's own goals; a row filters the feed to that player. */
export function DaySummary({ sub, rows }: { sub: string; rows: readonly SummaryRow[] }) {
  return (
    <section className={nutrition.summary} aria-labelledby="day-summary">
      <div className={nutrition.summaryHead}>
        <h2 id="day-summary" className={nutrition.summaryTitle}>
          Сводка за день
        </h2>
        <span className={nutrition.sub}>{sub}</span>
      </div>
      <ul className={nutrition.rows}>
        {rows.map((row) => (
          <li key={row.id}>
            <Link
              href={row.href}
              scroll={false}
              className={row.selected ? `${nutrition.row} ${nutrition.rowOn}` : nutrition.row}
              aria-current={row.selected ? "true" : undefined}
            >
              <span className={nutrition.rowTop}>
                <span className={nutrition.av} aria-hidden="true">
                  {row.initials}
                </span>
                <span className={nutrition.rowNick}>{row.nickname}</span>
                <span className={`${nutrition.st} ${TONE[row.status.tone]}`}>{row.status.label}</span>
              </span>
              <span className={nutrition.rowLine}>
                <span>
                  {row.kcal === NO_DATA ? `${NO_DATA} · норма ${row.goal} ккал` : `${row.kcal} / ${row.goal} ккал`}
                </span>
                <span>{row.meals}</span>
              </span>
              <span className={nutrition.bar} aria-hidden="true">
                <span
                  className={row.warm ? `${nutrition.barFill} ${nutrition.barWarm}` : nutrition.barFill}
                  style={{ width: `${row.fill}%` }}
                />
              </span>
              <span className={nutrition.macros}>
                {row.macros.map((m) => (
                  <span key={m}>{m}</span>
                ))}
                <span>{row.water}</span>
              </span>
            </Link>
          </li>
        ))}
      </ul>
      <span className={nutrition.norm}>{NORM_NOTE}</span>
    </section>
  );
}
