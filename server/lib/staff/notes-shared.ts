/**
 * Limits, kinds and texts of the card's staff notes and images. No imports: the browser-side file input and the
 * server code use the same numbers, and a test pins them.
 */

export const NOTE_MAX_CHARS = 5000;
/** Decimal megabytes, as the user-facing text says «2 МБ». */
export const IMAGE_MAX_BYTES = 2_000_000;
export const IMAGE_KINDS = ["scheme", "roadmap", "mindmap"] as const;
export type ImageKind = (typeof IMAGE_KINDS)[number];
/** UX only (the file dialog's filter); the server decides by the bytes. */
export const IMAGE_ACCEPT = "image/png,image/jpeg,image/webp";
export const IMAGE_TOO_BIG_TEXT = "Файл больше 2 МБ";

export const IMAGE_TITLES: Record<ImageKind, string> = {
  scheme: "Схема общения",
  roadmap: "Roadmap",
  mindmap: "Mindmap",
};

export type NoticeScope = "note" | ImageKind | "form";
export interface Notice {
  scope: NoticeScope;
  text: string;
  tone: "ok" | "error";
}

const FAILED_TEXT = "Не удалось выполнить действие. Обновите страницу и попробуйте ещё раз.";

/** Every outcome an action can redirect with (`?notice=`); anything else in the URL shows nothing. */
const NOTICES: Readonly<Record<string, Notice>> = {
  failed: { scope: "form", text: FAILED_TEXT, tone: "error" },
  "note-saved": { scope: "note", text: "Сохранено", tone: "ok" },
  "note-too-long": { scope: "note", text: `Заметка длиннее ${NOTE_MAX_CHARS} символов`, tone: "error" },
  ...Object.fromEntries(
    IMAGE_KINDS.flatMap((kind) => [
      [`${kind}-saved`, { scope: kind, text: "Загружено", tone: "ok" }],
      [`${kind}-deleted`, { scope: kind, text: "Удалено", tone: "ok" }],
      [`${kind}-size`, { scope: kind, text: IMAGE_TOO_BIG_TEXT, tone: "error" }],
      [`${kind}-type`, { scope: kind, text: "Нужен файл PNG, JPEG или WebP", tone: "error" }],
      [`${kind}-empty`, { scope: kind, text: "Выберите файл", tone: "error" }],
    ]),
  ),
};

export function noticeOf(token: string | undefined): Notice | null {
  return token !== undefined && Object.hasOwn(NOTICES, token) ? NOTICES[token] : null;
}

export type NoticeToken = "failed" | "note-saved" | "note-too-long" | `${ImageKind}-${"saved" | "deleted" | "size" | "type" | "empty"}`;
