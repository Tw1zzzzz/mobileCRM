"use client";

import styles from "./card.module.css";

interface FilterSelectProps {
  /** The element id; the label points at it. */
  id: string;
  /** The query parameter the select sets. */
  name: string;
  label: string;
  action: string;
  /** The current selection as query pairs (without `name`): kept when the form is submitted. */
  hidden: readonly (readonly [string, string])[];
  value: string;
  options: readonly { value: string; label: string; disabled?: boolean }[];
}

/**
 * A card filter («Тип теста», «Источник игр»): a GET form, so the choice lives in the URL. It submits on change;
 * without JavaScript the «Показать» button does the same.
 */
export function FilterSelect({ id, name, label, action, hidden, value, options }: FilterSelectProps) {
  return (
    <form method="get" action={action} className={styles.filterForm}>
      {hidden.map(([key, v]) => (
        <input key={key} type="hidden" name={key} value={v} />
      ))}
      <label htmlFor={id} className={styles.filterLabel}>
        {label}
      </label>
      <select
        id={id}
        name={name}
        className={styles.select}
        defaultValue={value}
        onChange={(e) => e.currentTarget.form?.requestSubmit()}
      >
        {options.map((o) => (
          <option key={o.value} value={o.value} disabled={o.disabled}>
            {o.label}
          </option>
        ))}
      </select>
      <noscript>
        <button type="submit" className={styles.select}>
          Показать
        </button>
      </noscript>
    </form>
  );
}
