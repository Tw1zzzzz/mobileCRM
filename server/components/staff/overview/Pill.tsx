import { pillLabel } from "@/lib/staff/overview-format";
import type { Pill as PillValue } from "@/lib/staff/metrics";
import styles from "./overview.module.css";

const CLASS: Record<PillValue, string> = {
  good: styles.pillGood,
  mid: styles.pillMid,
  bad: styles.pillBad,
};

/** «Отлично» / «Средне» / «Плохо»; nothing at all when there is no value to rate. */
export function Pill({ pill }: { pill: PillValue | null }) {
  if (pill === null) return null;
  return (
    <span className={`${styles.pill} ${CLASS[pill]}`}>
      <i aria-hidden="true" />
      {pillLabel(pill)}
    </span>
  );
}
