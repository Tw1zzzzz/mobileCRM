import type { ReactNode } from "react";
import { deleteImageAction, uploadImageAction } from "@/app/staff/actions";
import type { ImageView } from "@/lib/staff/notes";
import { IMAGE_TITLES, type ImageKind, type Notice } from "@/lib/staff/notes-shared";
import { FileInput } from "./FileInput";
import { HiddenFields } from "./HiddenFields";
import styles from "./card.module.css";

/** The board's drop copy and icon for each image. */
const SLOTS: Record<ImageKind, { hint: string; icon: ReactNode }> = {
  scheme: {
    hint: "Загрузите изображение схемы",
    icon: (
      <>
        <rect x="3" y="3" width="18" height="18" rx="3" />
        <circle cx="9" cy="9" r="2" />
        <path d="M21 15l-5-5L5 21" />
      </>
    ),
  },
  roadmap: { hint: "План развития игрока", icon: <path d="M4 19l5-14 6 10 5-6" /> },
  mindmap: {
    hint: "Карта мышления",
    icon: (
      <>
        <circle cx="12" cy="12" r="3" />
        <circle cx="4" cy="5" r="2" />
        <circle cx="20" cy="5" r="2" />
        <circle cx="4" cy="19" r="2" />
        <circle cx="20" cy="19" r="2" />
        <path d="M6 6l4 4M18 6l-4 4M6 18l4-4M18 18l-4-4" />
      </>
    ),
  },
};

/** One of the three staff images: a drop with «Загрузить», or the image with «Заменить» and «Удалить». */
export function ImageSlot({
  kind,
  playerId,
  hidden,
  image,
  notice,
}: {
  kind: ImageKind;
  playerId: string;
  hidden: readonly (readonly [string, string])[];
  image: ImageView | null;
  notice: Notice | null;
}) {
  const title = IMAGE_TITLES[kind];
  const { hint, icon } = SLOTS[kind];
  const upload = (button: string) => (
    <form action={uploadImageAction} className={styles.uploadForm}>
      <HiddenFields pairs={hidden} />
      <input type="hidden" name="kind" value={kind} />
      <FileInput name="file" label={`Файл: ${title}`} />
      <button type="submit" className={styles.btnGhost}>
        {button}
      </button>
    </form>
  );
  return (
    <div className={styles.slot}>
      <h3 className={styles.grp}>{title}</h3>
      {image === null ? (
        <div className={styles.dropBox}>
          <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            {icon}
          </svg>
          {hint}
          {upload("Загрузить")}
        </div>
      ) : (
        <div className={styles.imageBox}>
          <img src={`/staff/files/${playerId}/${kind}?v=${image.version}`} alt={title} className={styles.slotImage} />
          <div className={styles.imageActions}>
            {upload("Заменить")}
            <form action={deleteImageAction}>
              <HiddenFields pairs={hidden} />
              <input type="hidden" name="kind" value={kind} />
              <button type="submit" className={styles.btnGhost}>
                Удалить
              </button>
            </form>
          </div>
        </div>
      )}
      {notice !== null && (
        <span role={notice.tone === "error" ? "alert" : "status"} className={notice.tone === "error" ? styles.statusError : styles.statusOk}>
          {notice.text}
        </span>
      )}
    </div>
  );
}
