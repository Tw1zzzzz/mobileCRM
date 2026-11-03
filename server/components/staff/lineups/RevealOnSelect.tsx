"use client";

import { useEffect, useRef } from "react";

/** The entry the person had open before this render, kept across the remount that a new selection causes. */
let lastSeen: string | null = null;

/**
 * On a phone the detail panel sits below the whole list: when another entry is picked there, bring the panel into
 * view. Beside the list (desktop) nothing moves, and neither does the first render of the page.
 */
export function RevealOnSelect({ entryId, listSelector }: { entryId: string; listSelector: string }) {
  const marker = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    const previous = lastSeen;
    lastSeen = entryId;
    const panel = marker.current?.closest("aside");
    const list = panel ? document.querySelector(listSelector) : null;
    if (!panel || !list || previous === null || previous === entryId) return;
    const stacked = panel.getBoundingClientRect().top >= list.getBoundingClientRect().bottom - 1;
    if (stacked) panel.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [entryId, listSelector]);
  return <span ref={marker} hidden />;
}
