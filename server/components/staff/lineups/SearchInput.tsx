"use client";

import { useEffect, useRef } from "react";

const DEBOUNCE_MS = 350;

/** The search box: submits its form shortly after typing stops (Enter works without scripts). */
export function SearchInput({ defaultValue, className }: { defaultValue: string; className: string }) {
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  return (
    <input
      className={className}
      type="search"
      name="q"
      aria-label="Поиск по базе"
      placeholder="Поиск: название, карта, описание"
      defaultValue={defaultValue}
      maxLength={100}
      onChange={(event) => {
        const form = event.currentTarget.form;
        clearTimeout(timer.current);
        timer.current = setTimeout(() => form?.requestSubmit(), DEBOUNCE_MS);
      }}
    />
  );
}
