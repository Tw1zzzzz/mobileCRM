import type { FaceitNoticeData } from "@/lib/staff/card-game";
import styles from "./card.module.css";

/** What is wrong with the Faceit data (not connected, not found, down), or that the numbers are an older copy. */
export function FaceitNotice({ notice }: { notice: FaceitNoticeData | null }) {
  if (notice === null) return null;
  return (
    <p role="status" className={styles.notice}>
      {notice.message}
      {notice.asOf !== null && `. Данные от ${notice.asOf}`}
    </p>
  );
}
