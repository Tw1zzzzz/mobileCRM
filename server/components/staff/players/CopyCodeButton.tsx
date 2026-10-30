"use client";

import { useEffect, useState } from "react";
import styles from "./players.module.css";

type CopyState = "idle" | "copied" | "failed";

/** Copies a team code to the clipboard and says so. The only client component of the page. */
export function CopyCodeButton({ code, label }: { code: string; label: string }) {
  const [state, setState] = useState<CopyState>("idle");

  useEffect(() => {
    if (state === "idle") return;
    const timer = setTimeout(() => setState("idle"), 2000);
    return () => clearTimeout(timer);
  }, [state]);

  async function copy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(code);
      setState("copied");
    } catch {
      setState("failed");
    }
  }

  return (
    <>
      <button type="button" className={styles.btn} onClick={copy} aria-label={`Копировать ${label}`}>
        Копировать
      </button>
      <span className={styles.copied} role="status">
        {state === "copied" ? "Скопировано" : state === "failed" ? "Не удалось скопировать" : ""}
      </span>
    </>
  );
}
