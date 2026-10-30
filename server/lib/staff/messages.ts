import { ApiError, type ErrorCode } from "@/lib/http";

/** Texts of the app's sign-in and reset screens (core `AuthError`), so the website says the same things. */
export const MESSAGES = {
  emptyFields: "Заполните обязательные поля",
  passwordMismatch: "Введенные пароли не совпадают",
  server: "Не удалось выполнить вход. Повторите попытку позже",
} as const;

const BY_CODE: Partial<Record<ErrorCode, string>> = {
  invalid_credentials: "Неверный логин или пароль",
  weak_password: "Пароль должен содержать не менее 8 символов, включая буквы и цифры",
  invalid_code: "Неверный код",
  code_expired: "Срок действия кода истёк. Запросите новый",
  too_many_attempts: "Слишком много попыток. Запросите новый код",
  rate_limited: "Слишком много запросов. Попробуйте через несколько минут",
  mail_unavailable: "Не удалось отправить письмо с кодом. Попробуйте позже",
};

/** The text for a failed sign-in or reset; anything unexpected is logged and shown as the generic server error. */
export function authMessage(error: unknown): string {
  if (error instanceof ApiError) return BY_CODE[error.code] ?? MESSAGES.server;
  console.error("staff auth failed", error);
  return MESSAGES.server;
}

/** «1 игрок», «2 игрока», «5 игроков». */
export function playersLabel(count: number): string {
  const last = count % 10;
  const lastTwo = count % 100;
  const word =
    last === 1 && lastTwo !== 11
      ? "игрок"
      : last >= 2 && last <= 4 && (lastTwo < 10 || lastTwo >= 20)
        ? "игрока"
        : "игроков";
  return `${count} ${word}`;
}
