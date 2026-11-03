"use client";

import { useEffect, useState } from "react";
import styles from "./lineups.module.css";

const VISIBLE_MS = 2600;

/** The board's toast: shown for a moment after a save, and gone on its own. */
export function Toast({ message }: { message: string }) {
  const [shown, setShown] = useState(message);
  useEffect(() => {
    setShown(message);
    const timer = setTimeout(() => setShown(""), VISIBLE_MS);
    return () => clearTimeout(timer);
  }, [message]);
  return shown ? <div className={styles.toast} role="status">{shown}</div> : null;
}
