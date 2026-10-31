import Link from "next/link";
import { NUTRITION_PATH } from "@/lib/staff/nutrition";
import styles from "./players.module.css";

const NUTRITION_HREF = `${NUTRITION_PATH}?section=nutrition`;

/**
 * The two sections of «Игроки». The current one is plain text, the other a link; going to either keeps nothing
 * else of the URL.
 */
export function Segments({ active }: { active: "roster" | "nutrition" }) {
  return (
    <div className={styles.segments} role="group" aria-label="Раздел игроков">
      {active === "roster" ? (
        <span className={`${styles.tab} ${styles.tabOn}`} aria-current="page">
          Состав и карточки
        </span>
      ) : (
        <Link href={NUTRITION_PATH} className={styles.tab}>
          Состав и карточки
        </Link>
      )}
      {active === "nutrition" ? (
        <span className={`${styles.tab} ${styles.tabOn}`} aria-current="page">
          Питание команды
        </span>
      ) : (
        <Link href={NUTRITION_HREF} className={styles.tab}>
          Питание команды
        </Link>
      )}
    </div>
  );
}
