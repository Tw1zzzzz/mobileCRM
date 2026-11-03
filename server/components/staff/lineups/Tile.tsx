import type { ReactNode } from "react";
import type { StaffLineup } from "@/lib/staff/lineups";
import { TYPE_ICONS, tileBackground } from "./meta";
import styles from "./lineups.module.css";

interface TileProps {
  entry: StaffLineup;
  /** The large tile of the detail panel. */
  large?: boolean;
  children?: ReactNode;
}

/** The coloured tile of an entry: the map's tint, the type icon and the side badge. */
export function Tile({ entry, large = false, children }: TileProps) {
  const icon = TYPE_ICONS[entry.type];
  const size = large ? 64 : 44;
  return (
    <div className={large ? `${styles.thumb} ${styles.thumbLarge}` : styles.thumb} style={{ background: tileBackground(entry.map) }}>
      <span className={`${styles.side} ${entry.side === "t" ? styles.sideT : styles.sideCT}`}>{entry.side.toUpperCase()}</span>
      <svg
        className={styles.thumbIcon}
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke={icon.color}
        strokeWidth={large ? 1.6 : 1.8}
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <path d={icon.path} />
      </svg>
      {children}
    </div>
  );
}
