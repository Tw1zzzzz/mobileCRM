import type { ReactNode } from "react";
import styles from "./game-stats.module.css";

export const cx = (...names: (string | false | null | undefined)[]): string => names.filter(Boolean).join(" ");

/** The number badge, title and one-line explanation that open each step of the form. */
export function StepHead({ n, id, title, sub }: { n: number; id: string; title: string; sub: string }) {
  return (
    <div className={styles.stepHead}>
      <span className={styles.stepNum} aria-hidden="true">{n}</span>
      <div className={styles.stepText}>
        <h2 id={id}>{title}</h2>
        <span className={styles.stepSub}>{sub}</span>
      </div>
    </div>
  );
}

/** A 24x24 stroke icon. */
export function Icon({ path, size = 16, strokeWidth = 2, children }: { path?: string; size?: number; strokeWidth?: number; children?: ReactNode }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {path ? <path d={path} /> : children}
    </svg>
  );
}

export const CHECK = "M5 12l5 5L20 7";
