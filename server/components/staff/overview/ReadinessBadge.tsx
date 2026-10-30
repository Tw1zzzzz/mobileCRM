import { NO_DATA } from "@/lib/staff/overview-format";
import styles from "./overview.module.css";

/** The header widget: team readiness today, 0–100. */
export function ReadinessBadge({ value }: { value: number | null }) {
  const label =
    value === null ? `Готовность команды сегодня: ${NO_DATA}` : `Готовность команды сегодня: ${value} из 100`;
  return (
    <div role="img" aria-label={label} className={styles.readiness}>
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#3FD0FF" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M3 12h4l3-8 4 16 3-8h4" />
      </svg>
      {value === null ? (
        <span className={styles.readinessLabel}>готовность: {NO_DATA}</span>
      ) : (
        <>
          <span className={styles.readinessValue}>{value}</span>
          <span className={styles.readinessLabel}>готовность</span>
        </>
      )}
    </div>
  );
}
