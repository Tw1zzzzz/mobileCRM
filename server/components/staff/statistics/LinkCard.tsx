import type { LinkView } from "@/lib/staff/statistics-view";
import styles from "./statistics.module.css";

/** One key link: `{A} ↔ {B}`, r, the bar from the centre, the strength, and the median-split sentence. */
export function LinkCard({ link }: { link: LinkView }) {
  return (
    <article className={styles.link}>
      <div className={styles.linkHead}>
        <span className={styles.linkTitle}>{link.title}</span>
        <span className={`${styles.linkR} ${link.tone === "up" ? styles.up : styles.down}`}>{`r = ${link.r}`}</span>
      </div>
      <div className={styles.rbar} role="img" aria-label={link.aria}>
        <span
          className={`${styles.rfill} ${link.tone === "up" ? styles.rpos : styles.rneg}`}
          style={{ left: link.left, width: link.width }}
        />
      </div>
      <div className={styles.axis}>
        <span>−1 обратная</span>
        <span>0</span>
        <span>+1 прямая</span>
      </div>
      <span className={styles.strength}>{link.strength}</span>
      {link.example !== null && (
        <div className={styles.example}>
          <span className={styles.exampleTitle}>Что это значит</span>
          <span className={styles.exampleText}>{link.example}</span>
        </div>
      )}
    </article>
  );
}
