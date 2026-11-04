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

/** The profile page's notices. */
export const PROFILE_NOTICES = {
  saved: "Сохранено",
  emailChanged: "Почта изменена",
  passwordChanged: "Пароль изменён",
} as const;

const PROFILE_BY_CODE: Partial<Record<ErrorCode, string>> = {
  ...BY_CODE,
  invalid_email: "Укажите корректный адрес электронной почты",
  email_taken: "Этот email уже зарегистрирован",
};

const PROFILE_BY_FIELD: Record<string, string> = {
  nickname_empty: MESSAGES.emptyFields,
  nickname_long: "Не больше 40 символов",
  faceit_url: "Укажите корректную ссылку на профиль Faceit",
};

export const PROFILE_FAILED = "Не удалось выполнить действие. Повторите попытку позже";

/** The text for a failed profile action, in the app's words; anything unexpected is logged and shown generically. */
export function profileMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    console.error("staff profile action failed", error);
    return PROFILE_FAILED;
  }
  const field = (error.meta as { field?: string } | undefined)?.field;
  if (error.code === "validation_failed" && field !== undefined) return PROFILE_BY_FIELD[field] ?? PROFILE_FAILED;
  return PROFILE_BY_CODE[error.code] ?? PROFILE_FAILED;
}
