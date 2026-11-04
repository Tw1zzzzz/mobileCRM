import styles from "./profile.module.css";

interface FieldProps {
  id: string;
  label: string;
  name: string;
  type?: "text" | "email" | "password" | "url";
  autoComplete?: string;
  value?: string;
  onChange?: (value: string) => void;
}

/** A labelled input; controlled when `value` is given (so a typo does not clear what was typed). */
export function Field({ id, label, name, type = "text", autoComplete, value, onChange }: FieldProps) {
  const controlled = value !== undefined && onChange !== undefined;
  return (
    <div className={styles.field}>
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        name={name}
        type={type}
        autoComplete={autoComplete}
        className={styles.input}
        {...(controlled ? { value, onChange: (e) => onChange(e.target.value) } : {})}
      />
    </div>
  );
}
