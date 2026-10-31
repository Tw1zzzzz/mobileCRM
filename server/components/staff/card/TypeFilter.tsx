"use client";

import styles from "./card.module.css";

interface TypeFilterProps {
  action: string;
  /** The current selection as query pairs (without `type`): kept when the form is submitted. */
  hidden: readonly (readonly [string, string])[];
  value: string;
  options: readonly { value: string; label: string }[];
}

/**
 * «Тип теста»: a GET form, so the choice lives in the URL. It submits on change; without JavaScript the
 * «Показать» button does the same.
 */
export function TypeFilter({ action, hidden, value, options }: TypeFilterProps) {
  return (
    <form method="get" action={action} className={styles.filterForm}>
      {hidden.map(([name, v]) => (
        <input key={name} type="hidden" name={name} value={v} />
      ))}
      <label htmlFor="f-type" className={styles.filterLabel}>
        Тип теста
      </label>
      <select
        id="f-type"
        name="type"
        className={styles.select}
        defaultValue={value}
        onChange={(e) => e.currentTarget.form?.requestSubmit()}
      >
        {options.map((o) => (
          <option key={o.value} value={o.value}>
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
