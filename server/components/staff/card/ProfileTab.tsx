import type { Archetype } from "@/lib/staff/archetype";
import { NO_DATA } from "@/lib/staff/overview-format";
import styles from "./card.module.css";

/**
 * «Профиль»: the «Архетип» card. «Роль в CS2» is not built (the questionnaire stays unreadable for staff).
 * The «Коммуникативная линия» notes and the three image drops go below the grid (Task 8).
 */
export function ProfileTab({ archetype }: { archetype: Archetype | null }) {
  return (
    <div className={styles.profile}>
      <div className={styles.profileGrid}>
        <section className={styles.profileCard} aria-labelledby="arch-title">
          <h3 id="arch-title" className={styles.grp}>
            Архетип
          </h3>
          {archetype === null ? (
            <p className={styles.profileEmpty}>{NO_DATA}</p>
          ) : (
            <>
              <span className={styles.archName}>{archetype.title}</span>
              <span className={styles.archText}>{archetype.summary}</span>
              {archetype.tags.length > 0 && (
                <ul className={styles.tags}>
                  {archetype.tags.map((tag) => (
                    <li key={tag} className={styles.tag}>
                      {tag}
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </section>
      </div>
    </div>
  );
}
