"use client";

import { useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";
import styles from "./lineups.module.css";

interface DialogProps {
  titleId: string;
  /** Where Escape, the backdrop and ✕ lead: the same list without the dialog. */
  closeHref: string;
  children: ReactNode;
}

/** The board's modal over the page; its state is the URL, so closing is a navigation. */
export function Dialog({ titleId, closeHref, children }: DialogProps) {
  const router = useRouter();
  useEffect(() => {
    const close = (event: KeyboardEvent) => {
      if (event.key === "Escape") router.push(closeHref, { scroll: false });
    };
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    document.addEventListener("keydown", close);
    return () => {
      document.body.style.overflow = overflow;
      document.removeEventListener("keydown", close);
    };
  }, [closeHref, router]);
  return (
    <div
      className={styles.overlay}
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) router.push(closeHref, { scroll: false });
      }}
    >
      <div className={styles.dlg} role="dialog" aria-modal="true" aria-labelledby={titleId}>
        {children}
      </div>
    </div>
  );
}
