import { saveNoteAction } from "@/app/staff/actions";
import type { NoteView } from "@/lib/staff/notes";
import { NOTE_MAX_CHARS, type Notice } from "@/lib/staff/notes-shared";
import { HiddenFields } from "./HiddenFields";
import styles from "./card.module.css";

/** «Коммуникативная линия»: one note per player, shared by the team's staff; last write wins. Plain text only. */
export function NotesForm({
  hidden,
  note,
  notice,
}: {
  hidden: readonly (readonly [string, string])[];
  note: NoteView | null;
  notice: Notice | null;
}) {
  return (
    <form action={saveNoteAction} className={styles.noteForm}>
      <HiddenFields pairs={hidden} />
      <h3 className={styles.grp}>Коммуникативная линия</h3>
      <span className={styles.sub}>
        Круг общения в команде, статус общения, открытые вопросы — заметки видит только staff
      </span>
      <label htmlFor="comm" className={styles.srOnly}>
        Заметки о коммуникации
      </label>
      <textarea id="comm" name="text" className={styles.note} maxLength={NOTE_MAX_CHARS} defaultValue={note?.text ?? ""} />
      <div className={styles.noteRow}>
        <button type="submit" className={styles.btnGhost}>
          Сохранить
        </button>
        {notice !== null && (
          <span role={notice.tone === "error" ? "alert" : "status"} className={notice.tone === "error" ? styles.statusError : styles.statusOk}>
            {notice.text}
          </span>
        )}
        {note !== null && <span className={styles.noteMeta}>{note.changed}</span>}
      </div>
    </form>
  );
}
