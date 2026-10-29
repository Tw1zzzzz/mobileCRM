# Pulse API — контракт для мобильного приложения

Мобильное приложение уже содержит клиент для этого API (`Pulse/Core/API`, `RemoteAuthService`, `SyncEngine`).
Сейчас он выключен: первый релиз хранит данные на телефоне. Чтобы включить сервер, в `AppConfig.swift` указывается
`backend = .remote(URL(string: "https://api.pulse.app/v1")!)` — экраны менять не нужно.

Требование ТЗ: «сохранить совместимость с текущей бизнес-логикой, бэкендом и базой данных» — API лучше добавить
в существующий бэкенд веб-версии, а не делать отдельную базу.

## Общие правила

- Базовый адрес: `https://<домен>/v1`, только HTTPS.
- JSON, имена полей — `snake_case`, даты — ISO 8601 в UTC (`2026-10-14T18:30:00Z`).
- Все идентификаторы записей — UUID, **создаются на телефоне** (повторная отправка не создаёт дубль — upsert по `id`).
- Авторизация: `Authorization: Bearer <access_token>`. Access-токен живёт ~15 минут, refresh — ~30 дней.
- Ответ всегда в конверте:

```json
{ "success": true, "data": { … }, "error": null, "meta": { "page": 1, "limit": 50, "total": 120 } }
{ "success": false, "data": null, "error": { "code": "email_taken", "message": "…" } }
```

- Коды ошибок, которые приложение показывает текстами из ТЗ:

| code | HTTP | Текст в приложении |
|---|---|---|
| `invalid_credentials` | 401 | Неверный логин или пароль |
| `email_taken` | 409 | Пользователь с таким адресом электронной почты уже существует |
| `team_not_found` | 404 | Команда с таким кодом не найдена |
| `invalid_email` | 422 | Укажите корректный адрес электронной почты |
| `weak_password` | 422 | Пароль должен содержать не менее 8 символов, включая буквы и цифры |
| `token_expired` | 401 | (приложение само вызывает `/auth/refresh`) |
| прочее / 5xx | | Не удалось выполнить вход / регистрацию. Повторите попытку позже |

- Лимиты: на `/auth/*` — не больше 10 запросов в минуту с IP; на `/photos` — 600 в минуту на пользователя; на остальное — 120 в минуту на пользователя.

## Auth

| Метод | Путь | Тело | Ответ `data` |
|---|---|---|---|
| POST | `/auth/login` | `{ email, password }` | `{ user, access_token, refresh_token }` |
| POST | `/auth/register` | `{ nickname, email, password, role, faceit_url?, team_code?, team_name?, accepted_terms, accepted_privacy }` | `{ user, access_token, refresh_token }` |
| POST | `/auth/refresh` | `{ refresh_token }` | `{ access_token, refresh_token }` |
| POST | `/auth/logout` | `{ refresh_token }` | `{}` |
| POST | `/auth/password-reset` | `{ email }` | `{}` (письмо со ссылкой) |

`role`: `soloPlayer` · `teamPlayer` · `staff`. Для `teamPlayer` обязателен `team_code` (проверка кода → `team_not_found`),
для `staff` — `team_name` (создаёт команду и возвращает её код для приглашения игроков).

`user`:

```json
{ "id": "uuid", "nickname": "Nova", "email": "nova@team.gg", "role": "teamPlayer", "tariff": "free",
  "team": "Pulse Esports", "streak_days": 12, "faceit_url": "https://www.faceit.com/ru/players/Nova" }
```

## Профиль

| Метод | Путь | Описание |
|---|---|---|
| GET | `/me` | текущий пользователь |
| PATCH | `/me` | `{ nickname?, faceit_url? }` |
| DELETE | `/me` | удаление аккаунта и всех данных (требование App Store) |
| POST | `/me/devices` | `{ apns_token, platform: "ios" }` — для пушей |

## Синхронизация данных (этап 1 — документы)

Приложение хранит данные коллекциями (один JSON-документ на коллекцию, имя коллекции — имя файла без `.json`)
и отмечает изменённые (`SyncLedger`). На первом этапе сервер хранит документ целиком на пользователя, с номером
версии, чтобы один телефон не затёр правки другого:

| Метод | Путь | Тело | Ответ `data` |
|---|---|---|---|
| GET | `/sync` | — | список `[{ collection, version, updated_at }]` (только сохранённые коллекции) |
| GET | `/sync/{collection}` | — | `{ collection, version, updated_at, document }`; документа нет → `404 not_found` |
| PUT | `/sync/{collection}` | `{ base_version, document }` | `{ version, updated_at }` |

`base_version` — версия, на которой основана правка (`null` — первая запись коллекции; ключ обязателен, даже
с `null`). Проверка версии и запись — один атомарный запрос: из двух телефонов с одной и той же `base_version`
сохранится только один. Второй получит `409 version_conflict` и `meta.current_version` (`0`, если документа ещё нет):

```json
{ "success": false, "data": null, "error": { "code": "version_conflict", "message": null }, "meta": { "current_version": 4 } }
```

Приложение в этом случае забирает свежий документ (`GET /sync/{collection}`), сливает записи по `id` и отправляет
снова (до 3 попыток за раунд).

Ограничения и ошибки:

- Допустимы 12 коллекций из таблицы ниже; любое другое имя → `422 validation_failed`.
- `document` — любой JSON, кроме `null` (`null` → `422 validation_failed`); размер тела запроса ≤ 4 000 000 байт, больше → `413 payload_too_large`.
- Символ `\u0000` в строках не принимается (`422 validation_failed`).
- Тело не является JSON → `400 bad_request`.
- Лимит — 120 запросов в минуту на пользователя для `/sync` (сверх лимита — `429 rate_limited`). У `/photos` свой лимит — 600 в минуту: фото идут по одному запросу на блюдо и не должны съедать лимит документов.

Новые коды ошибок:

| code | HTTP | Когда |
|---|---|---|
| `not_found` | 404 | нет документа коллекции или фото блюда |
| `version_conflict` | 409 | `base_version` не совпала с текущей версией; в `meta` — `current_version` |
| `payload_too_large` | 413 | документ больше 4 000 000 байт или фото больше 1 000 000 байт |

### Фото блюд

Фото не лежат внутри `nutrition`: иначе месяц приёмов пищи не уместился бы в лимит документа. В документе,
который уходит на сервер, у блюда нет поля `photo`, вместо него — `"photoRef": true`; само фото (JPEG) хранится отдельно
по `id` блюда. На телефоне фото остаётся в блюде как base64.

| Метод | Путь | Тело | Ответ |
|---|---|---|---|
| PUT | `/photos/{meal_id}` | сырой JPEG, не JSON | `data: {}` |
| GET | `/photos/{meal_id}` | — | сырой JPEG (`image/jpeg`, `Cache-Control: private, no-store`), без конверта; фото нет → `404 not_found` (в конверте) |

- `meal_id` — UUID блюда, иначе `422 validation_failed`.
- Тело PUT должно начинаться с сигнатуры JPEG (`FF D8 FF`), иначе `422 validation_failed`; размер ≤ 1 000 000 байт, больше → `413 payload_too_large`. Повторный PUT заменяет фото.
- Телефон сначала загружает фото, потом записывает `nutrition`. Каждая запись `nutrition` удаляет фото блюд, которых уже нет в документе (в одной транзакции с записью; отклонённая запись ничего не удаляет). Если в документе нет списка `meals`, фото не трогаются.
- Фото, которое сервер отклонил насовсем (слишком большое или не JPEG), телефон помечает и больше не шлёт; блюдо синхронизируется без фото и без `photoRef`.
- Временная ошибка фото (`429`, сеть, 5xx) не останавливает синхронизацию. Загрузка: оставшиеся фото ждут следующего раунда, их блюда уходят пока без `photoRef` (`nutrition` остаётся неотправленным, пока все фото не дошли); уже отправленные фото повторно шлются только после `409 version_conflict`. Скачивание: блюдо сохраняется с `photoRef`, фото докачивается в следующих раундах, а запись `nutrition` с телефона сохраняет его `photoRef`. На `404` блюдо остаётся без фото.

Коллекции и их содержимое (модели — в `Pulse/Models`, `Pulse/Stores`). Ключи внутри документов — в camelCase, как их
пишет приложение (`PulseJson`), даты — ISO 8601:

| collection | Что внутри |
|---|---|
| `sleep` | `sessions[] {id, start, end}`, `activeStart`, `window {bedtimeHour, bedtimeMinute, wakeHour, wakeMinute}` |
| `nutrition` | `meals[] {id, date, name, kcal, protein, fat, carbs, photoRef (true — фото блюда лежит на сервере), source, author}`, `water {day: ml}`, `goals` |
| `wellbeing` | `mood[] {id, day, slot, mood, energy}`, `balance[] {id, day, values}` |
| `cognitive` | `results[] {id, kind, date, score, metric}`, `gameResults[] {id, date, phase, focus, fatigue, readiness}` |
| `calendar` | документ — сам массив событий: `[{id, title, details, start, end, links, tag, ownerId}]` |
| `fitness-state` | `workouts[] {id, kind, detail, date, minutes, kcal, averageHeartRate, rpe}`, `goals`, `ratings`, `healthConnected` |
| `screen-time` | `entries[] {date, minutes}`, `limitMinutes`, `accessGranted` |
| `lineups` | документ — сам массив раскидок/заметок дневника CS2: `[{id, …, visibility (personal / team), variations[]}]` |
| `game-goals` | `elo, eloStart, kd, adr, winRate` |
| `faceit` | `nickname, playerId, snapshots[] {date, elo}` |
| `player-survey` | `survey {goals[], role, level, yearsPlaying, hoursPerWeek, playTime, motivation, updatedAt}`, `mindset[] {id, date, answers {q1…q24: 1–5}}`, `onboardingDismissed` |
| `body-profile` | шаг анкеты «О тебе»: `birthDate`, `heightCm`, `weightKg`, `updatedAt` |

> Для этапа 1 сервер хранит документ без разбора (кроме списка `meals` в `nutrition` — по нему чистятся фото);
> на этапе 2 (ниже) появятся нормальные таблицы.

**Этап 2 — построчная синхронизация** (когда понадобятся отчёты стаффа по всей базе):
`GET /sync/{collection}/changes?since=<ISO>` и `POST /sync/{collection}/batch { upserts[], deletes[] }`,
конфликты — «последняя правка записи побеждает» по `updated_at`.

## Стафф и команда

| Метод | Путь | Описание |
|---|---|---|
| GET | `/teams/{team_id}/players` | состав: ник, роль, последняя активность, ELO |
| GET | `/teams/{team_id}/players/{player_id}/summary?days=7` | готовность, сон, тесты, нагрузка (ACWR), питание |
| GET | `/teams/{team_id}/analytics?days=30` | готовность команды, корреляции (считаются на сервере) |
| GET | `/teams/{team_id}/food?days=7` | лента еды игроков (фото — подписанные URL) |
| GET | `/teams/{team_id}/players/{player_id}/profile` | анкета игрока и последний «Игровой характер»: баллы 0–100 по 6 чертам и ведущая черта (ответы по пунктам стаффу не отдаются) |

Права проверяются **на сервере**: стафф видит только свою команду; личные записи (`visibility: personal`),
еда и дневник соло-игроков стаффу недоступны. Профиль «Игровой характер» видит только стафф команды игрока; соло-игроки — только сами.

## Faceit (сервер как прокси)

Ключ Faceit Data API хранится только на сервере. Сервер раз в N минут обновляет матчи и **сохраняет ELO по дням**
(в Data API нет истории ELO).

| Метод | Путь | Описание |
|---|---|---|
| GET | `/faceit/profile` | профиль CS2: ELO, уровень, регион, место в рейтинге, lifetime, карты |
| GET | `/faceit/matches?limit=100` | матчи с детальной статистикой (как `GameMatch.Details` в приложении) |
| GET | `/faceit/elo-history?days=90` | ELO по дням |

## Еда по фото (AI)

| Метод | Путь | Описание |
|---|---|---|
| POST | `/food/analyze` | `multipart/form-data` с фото → `{ job_id }` |
| GET | `/food/analyze/{job_id}` | `{ status: pending/done/failed, dish_name, kcal, protein, fat, carbs, confidence }` |

Ключ AI-сервиса — только на сервере. В приложении протокол `FoodAnalyzing` уже есть: серверная реализация
заменит ручной ввод без изменения экранов.

## Пуши

Сервер отправляет пуши через APNs (ключ `.p8` из Apple Developer): напоминания о событиях команды от стаффа,
игровые тесты до/после тренировки. Локальные напоминания на телефоне продолжают работать.
