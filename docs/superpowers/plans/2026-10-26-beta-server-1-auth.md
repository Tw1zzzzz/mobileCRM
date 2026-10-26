# Beta server 1/5 — foundation and sign-in Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A deployed Pulse beta API on Vercel with real sign-up (email code), sign-in, password reset, profile and account deletion — and the Android app using it end to end.

**Architecture:** `server/` is a Next.js (App Router, TypeScript) project; every endpoint is a plain handler `(req: Request, deps: Deps) => Promise<Response>` in `server/lib/**`, wired by a one-line `route.ts` under `server/app/v1/**`, so tests call handlers directly against a real local Postgres with an in-memory mailer and a fixed clock. Postgres access is plain SQL through postgres.js (portable off Vercel, see spec «Переносимость»). On the client, `core` gets a verification-aware `AuthService`, the backend is chosen per build (`BuildConfig.API_BASE_URL`), and the auth screen gains the code, reset and beta-terms steps.

**Tech Stack:** Next.js (latest stable, App Router) + TypeScript, postgres.js, zod, jose (JWT), @node-rs/argon2, resend, vitest; local Postgres 18 (Homebrew, already running); Kotlin 2.4 + Ktor 3.6 (`MockEngine` in tests), Jetpack Compose + Robolectric.

**Spec:** `docs/superpowers/specs/2026-10-26-beta-server-design.md` (this plan = stage 1 and the auth part of stage 2; sync, analytics, staff, landing are plans 2–5).

## Global Constraints

- Wire format exactly as `docs/API.md`: base `/v1`, JSON `snake_case`, ISO 8601 UTC, envelope `{ "success", "data", "error": { "code", "message" } | null, "meta"? }`.
- Error codes → HTTP: `bad_request` 400 (unparseable JSON), `validation_failed` 422, `invalid_email` 422, `weak_password` 422, `invalid_credentials` 401, `unauthorized` 401, `token_expired` 401, `email_not_verified` 403, `team_not_found` 404, `email_taken` 409, `invalid_code` 422, `code_expired` 422, `too_many_attempts` 429, `rate_limited` 429, `internal` 500.
- Access token: JWT HS256, `sub` = user id, lifetime 15 min, secret `AUTH_SECRET` (≥ 32 bytes, startup fails without it). Refresh token: 32 random bytes base64url, stored as SHA-256 hex, lifetime 30 days, rotated on every `/auth/refresh`.
- Email code: 6 digits from a CSPRNG, stored as HMAC-SHA256(`AUTH_SECRET`, `user_id:purpose:code`), valid 15 min, max 5 wrong attempts, a new code of the same purpose voids the old one. Purposes `verify`, `reset`.
- Limits: `/v1/auth/*` 10 requests/min per IP (first `x-forwarded-for` entry, else `x-real-ip`, else `unknown`); 5 codes/hour per email address.
- Passwords: argon2id; same rule as the app — ≥ 8 chars, at least one letter and one digit. Email: trimmed, lowercased, regex `^[A-Z0-9a-z._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$`. Nickname 1–40 chars trimmed. `faceit_url` optional, host `faceit.com` or `*.faceit.com`.
- Demo addresses `player@pulse.app`, `solo@pulse.app`, `staff@pulse.app` are never accounts on the server (`email_taken`); in the app they keep signing in locally, also in remote mode.
- Invite code: 6 chars from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, unique, compared uppercase/trimmed.
- Beta terms version constant: `2026-10-26` (server stores `beta_terms_version` + `beta_terms_accepted_at`).
- No secrets in the repo: `server/.env.example` lists names only; Android reads `pulse.apiBaseUrl` from `local.properties` or `PULSE_API_BASE_URL`.
- Testers get a remote build only after plan 5: until plan 4, a real staff account still sees the demo staff screens, and data is not synced yet (plan 2). Sentry is wired in plan 5.
- iOS untouched. Demo stays fully local. Kotlin follows the house rules: immutable models, `StateFlow` state, Russian copy.

Copy (verbatim):
- Emails — verify: subject «Код подтверждения Pulse», body «Ваш код: {code}. Он действует 15 минут. Если вы не регистрировались в Pulse, просто проигнорируйте это письмо.» Reset: subject «Сброс пароля Pulse», body «Код для сброса пароля: {code}. Он действует 15 минут. Если вы не запрашивали сброс, проигнорируйте письмо — пароль не изменится.»
- New `AuthError` texts: `INVALID_CODE` «Неверный код», `CODE_EXPIRED` «Срок действия кода истёк. Запросите новый», `TOO_MANY_ATTEMPTS` «Слишком много попыток. Запросите новый код», `RATE_LIMITED` «Слишком много запросов. Попробуйте через несколько минут», `BETA_TERMS_NOT_ACCEPTED` «Для продолжения необходимо принять условия бета-тестирования», `EMAIL_NOT_VERIFIED` «Подтвердите почту».
- Registration consent: «Я принимаю » + link «условия бета-тестирования» (remote mode only, third row). Team hints in remote mode: staff «После регистрации вы получите код команды — отправьте его игрокам.», player «Код команды выдаёт стафф.»
- Code step: title «Подтвердите почту», text «Мы отправили 6-значный код на {email}», field «Код из письма», button «Подтвердить», link «Отправить код ещё раз» / «Отправить ещё раз через 0:42» (60 s), link «Изменить почту».
- Reset: link «Забыли пароль?» under the password field (remote only); title «Сброс пароля»; step 1 field «Email», button «Получить код»; step 2 text «Мы отправили 6-значный код на {email}», fields «Код из письма», «Новый пароль», «Повторите пароль», button «Сменить пароль»; link «Назад ко входу».

## Review Focus

- A tester registers, loses the code (closed the app, typo in nothing) and registers **again with the same email** → gets a fresh code, not `email_taken`; the latest nickname/password/role win. Test `reRegisterUnverifiedSendsNewCode` (Task 4).
- Two screens fire requests at once with an expired access token → **one** refresh, both requests succeed, the user is not logged out by refresh-reuse detection. Tests `concurrentUnauthorizedRefreshesOnce` (Task 7) and `reusedRefreshTokenRevokesAll` (Task 5).
- Wrong code typed 5 times → `too_many_attempts`, and a new code (resend) works again. Test `fifthWrongCodeLocksUntilResend` (Task 4).
- Phone updated from a local-accounts build while signed in to a local account → after the update (remote mode) it lands on the sign-in screen, not in a session without tokens; demo sessions survive. Test `localSessionDroppedInRemoteMode` (Task 8).
- «Посмотреть демо» with no network in a remote build → demo opens, nothing is sent. Test `demoSignInNeedsNoServer` (Task 7).

---

### Task 1: Server skeleton, envelope, migrations, test harness

**Files:**
- Create: `server/package.json`, `server/tsconfig.json`, `server/next.config.ts`, `server/vitest.config.ts`, `server/.env.example`, `server/.gitignore`
- Create: `server/app/page.tsx` (one line «Pulse — бета», replaced in plan 5), `server/app/v1/health/route.ts`
- Create: `server/lib/http.ts`, `server/lib/deps.ts`, `server/db/sql.ts`, `server/db/migrate.ts`, `server/db/migrations/001_auth.sql`
- Test: `server/test/setup.ts`, `server/test/health.test.ts`
- Modify: root `.gitignore` (`server/node_modules`, `server/.next`, `server/.env*` except `.env.example`)

**Interfaces:**
- Produces: `ok(data: unknown, status = 200, meta?: object): Response`; `fail(code: ErrorCode, message: string | null, status: number): Response`; `type ErrorCode` = the Global Constraints list; `parseBody<T>(req: Request, schema: ZodType<T>): Promise<T>` throwing `ApiError` (→ `bad_request`/`validation_failed`); `class ApiError extends Error { code; status }`; `handle(fn: () => Promise<Response>): Promise<Response>` converting `ApiError` to `fail(...)` and anything else to `internal` 500 (logged with `console.error`, no stack in the body).
- Produces: `interface Deps { sql: Sql; mailer: Mailer; now: () => Date; secret: Uint8Array }`; `defaultDeps(): Deps` (memoized; reads `DATABASE_URL`, `AUTH_SECRET`; throws a clear error naming the missing variable). `Mailer` is defined in Task 3 — declare the interface here in `server/lib/mail.ts` to avoid a cycle.
- Produces: `npm run db:migrate` (applies `db/migrations/*.sql` in name order inside a transaction each, tracked in `schema_migrations(name text primary key, applied_at timestamptz)`); `npm test` (vitest); test helpers `testDeps(): Deps` (sql on `TEST_DATABASE_URL` default `postgres://localhost/pulse_test`, `MemoryMailer`, clock `2026-10-26T12:00:00Z` advanced by `advance(ms)`), `resetDb()` truncating every table except `schema_migrations`.
- Schema `001_auth.sql`: `teams(id uuid pk default gen_random_uuid(), name text not null, invite_code text unique not null, owner_id uuid, created_at timestamptz default now())`; `users(id uuid pk, email text unique not null, nickname text not null, password_hash text not null, role text not null check in ('soloPlayer','teamPlayer','staff'), tariff text not null default 'free', team_id uuid references teams on delete set null, faceit_url text, email_verified_at timestamptz, accepted_terms_at timestamptz not null, accepted_privacy_at timestamptz not null, beta_terms_version text not null, beta_terms_accepted_at timestamptz not null, install_source text not null default 'beta', created_at timestamptz not null, updated_at timestamptz not null)`; `teams.owner_id` FK to users `on delete cascade` added after `users`; `refresh_tokens(id uuid pk, user_id uuid references users on delete cascade, token_hash text unique not null, expires_at timestamptz not null, revoked_at timestamptz, replaced_by uuid, created_at timestamptz not null)`; `email_codes(id uuid pk, user_id uuid references users on delete cascade, purpose text check in ('verify','reset'), code_hash text not null, expires_at timestamptz not null, attempts int not null default 0, consumed_at timestamptz, created_at timestamptz not null)`; `rate_limits(key text, window_start timestamptz, count int not null, primary key (key, window_start))`.

- [ ] **Step 1:** Scaffold `server/` (Next.js App Router, TypeScript, no Tailwind needed yet), add deps `postgres zod jose @node-rs/argon2 resend` and dev deps `vitest tsx @types/node`; scripts `dev`, `build` = `npm run db:migrate && next build`, `db:migrate` = `tsx db/migrate.ts`, `test` = `vitest run`. vitest `globalSetup` = `test/setup.ts`: creates database `pulse_test` if missing and runs migrations on it.
- [ ] **Step 2: Write the failing test** `health.test.ts`: `GET` of `app/v1/health/route.ts` returns 200 with body `{ success: true, data: { ok: true, db: true }, error: null }`; `fail("email_taken", "x", 409)` returns status 409 and `{ success: false, data: null, error: { code: "email_taken", message: "x" } }`; `handle(() => { throw new Error("boom") })` → 500 with `error.code === "internal"` and the body not containing `boom`.
- [ ] **Step 3:** Run `cd server && npm test` — Expected: FAIL (modules missing).
- [ ] **Step 4:** Implement `http.ts`, `deps.ts`, `sql.ts`, `migrate.ts`, `001_auth.sql`, health route (`select 1`).
- [ ] **Step 5:** Run `cd server && npm run db:migrate` with `DATABASE_URL=postgres://localhost/pulse_test` then `npm test` — Expected: PASS.
- [ ] **Step 6: Commit** — `git add server .gitignore && git commit -m "feat(server): Next.js API skeleton, envelope, migrations"`

### Task 2: Passwords, tokens, rate limiter

**Files:**
- Create: `server/lib/auth/passwords.ts`, `server/lib/auth/tokens.ts`, `server/lib/rateLimit.ts`, `server/lib/auth/validation.ts`
- Test: `server/test/auth-primitives.test.ts`

**Interfaces:**
- Produces: `hashPassword(p: string): Promise<string>`, `verifyPassword(hash: string, p: string): Promise<boolean>` (argon2id).
- Produces: `issueSession(deps, userId: string): Promise<{ access_token: string; refresh_token: string }>`; `rotateRefresh(deps, refresh: string): Promise<{ userId: string; tokens: {...} }>` (throws `ApiError("unauthorized", 401)` for unknown/expired/revoked; a **revoked** token that was already replaced → revoke every token of that user, then throw); `revokeRefresh(deps, refresh: string): Promise<void>`; `revokeAll(deps, userId: string): Promise<void>`; `requireUser(deps, req: Request): Promise<string>` (Bearer JWT → user id; expired → `token_expired` 401; missing/bad → `unauthorized` 401).
- Produces: `rateLimit(deps, key: string, limit: number, windowMs: number): Promise<void>` throwing `ApiError("rate_limited", 429)`; fixed window keyed by `floor(now / windowMs)`; `clientIp(req: Request): string`.
- Produces: `normalizeEmail`, `isValidEmail`, `isStrongPassword`, `isValidFaceitUrl` (same rules as `AuthValidator` in `core/.../model/User.kt`).

- [ ] **Step 1: Write the failing tests:** `passwordRoundTrip` (verify true for the right password, false for another); `accessTokenExpiresAfter15Minutes` (`requireUser` ok at +14 min, `token_expired` at +16 min via `advance`); `refreshRotates` (old refresh rejected after rotation with `unauthorized`, new one works); `reusedRefreshTokenRevokesAll` (rotate A→B, present A again → 401, and B is now rejected too); `rateLimitBlocksEleventh` (10 calls pass, 11th throws `rate_limited`, next window passes); `validationMatchesApp` (`isStrongPassword("abcdefg1")` true, `"abcdefgh"` false, `"1234567a"` true; `normalizeEmail(" Nova@Team.GG ") === "nova@team.gg"`; faceit `https://www.faceit.com/ru/players/x` true, `https://evil.com` false).
- [ ] **Step 2:** Run `npm test -- auth-primitives` — Expected: FAIL.
- [ ] **Step 3:** Implement the four modules.
- [ ] **Step 4:** Run `npm test -- auth-primitives` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(server): password hashing, rotating tokens, rate limits`

### Task 3: Email codes and mailer

**Files:**
- Create: `server/lib/mail.ts` (interface from Task 1 + `ResendMailer`, `MemoryMailer`, `ConsoleMailer`), `server/lib/auth/codes.ts`
- Test: `server/test/codes.test.ts`

**Interfaces:**
- Produces: `interface Mailer { send(m: { to: string; subject: string; text: string }): Promise<void> }`; `MemoryMailer` with `sent: Array<{to, subject, text}>` and `lastCode(to: string): string | undefined` (parses the 6 digits); `defaultDeps()` picks `ResendMailer` when `RESEND_API_KEY` is set (from `MAIL_FROM`), else `ConsoleMailer` — and throws at startup if `VERCEL_ENV === "production"` without `RESEND_API_KEY`.
- Produces: `sendCode(deps, user: { id: string; email: string }, purpose: "verify" | "reset"): Promise<void>` (enforces 5/hour per email via `rateLimit(deps, "code:" + email, 5, 3_600_000)`, voids previous unconsumed codes of that purpose, sends the Global Constraints copy); `checkCode(deps, userId: string, purpose, code: string): Promise<void>` (consumes on success; throws `invalid_code` / `code_expired` / `too_many_attempts`).

- [ ] **Step 1: Write the failing tests:** `codeIsSixDigitsAndConsumedOnce` (mail text matches copy; correct code passes once, second use → `invalid_code`); `codeExpiresAfter15Minutes`; `fifthWrongCodeLocksUntilResend` (4 wrong → `invalid_code`, 5th wrong → `too_many_attempts`, the right code now also → `too_many_attempts`; after a new `sendCode` the new code passes); `newCodeVoidsOld`; `sixthCodeInAnHourIsRateLimited`.
- [ ] **Step 2:** Run `npm test -- codes` — Expected: FAIL.
- [ ] **Step 3:** Implement `mail.ts`, `codes.ts`.
- [ ] **Step 4:** Run `npm test -- codes` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(server): email codes over Resend`

### Task 4: Register and verify email

**Files:**
- Create: `server/lib/auth/register.ts`, `server/lib/auth/verify.ts`, `server/lib/users.ts`, `server/lib/teams.ts`
- Create routes: `server/app/v1/auth/register/route.ts`, `verify-email/route.ts`, `resend-code/route.ts`
- Test: `server/test/register.test.ts`

**Interfaces:**
- Produces: `registerHandler(req, deps)`, `verifyEmailHandler(req, deps)`, `resendCodeHandler(req, deps)`.
- Produces: `userJson(deps, userId): Promise<UserJson>` = `{ id, nickname, email, role, tariff, team: string | null (team name), team_id: string | null, invite_code: string | null (only for the team owner), streak_days: 0, faceit_url: string | null }` — used by every endpoint that returns a user.
- Produces: `createTeam(deps, name: string, ownerId: string): Promise<{ id, invite_code }>`; `findTeamByCode(deps, code: string): Promise<{ id, name } | null>`.
- Request body (register): `{ nickname, email, password, role, faceit_url?, team_code?, team_name?, accepted_terms, accepted_privacy, accepted_beta_terms, beta_terms_version }`. All three consents must be `true` → else `validation_failed`. Response 201 `{ pending_verification: true, email }`.
- Rules: rate limit by IP (`"auth:" + ip`, 10/min) on all three endpoints. Existing **verified** email or demo address → `email_taken` 409. Existing **unverified** email → overwrite nickname, password, role, team, faceit, consents and send a new code (201). `teamPlayer` → `team_code` must exist (`team_not_found` 404); `staff` → `team_name` required, team created with the user as owner. `verify-email { email, code }` → marks `email_verified_at`, returns 200 `{ user, access_token, refresh_token }`; unknown email → `invalid_code`. `resend-code { email }` → always 200 `{}`; sends only for an existing unverified user.

- [ ] **Step 1: Write the failing tests:** `soloRegistersVerifiesAndGetsSession` (201 → code from `MemoryMailer` → 200 with tokens and `user.role === "soloPlayer"`, `streak_days === 0`); `emailIsCaseInsensitive` (register `" Nova@Team.GG "`, verify with `"nova@team.gg"`); `verifiedEmailIsTaken`; `demoEmailIsTaken`; `reRegisterUnverifiedSendsNewCode` (second register 201, old code → `invalid_code`, new code works, nickname is the second one); `staffGetsTeamAndInviteCode` (user json has `invite_code` matching `/^[A-HJ-NP-Z2-9]{6}$/` and `team` = given name); `playerJoinsByCodeCaseInsensitive` (`" abc234 "` joins team `ABC234`); `unknownTeamCode404`; `consentsRequired` (each of three false → 422 `validation_failed`); `weakPasswordAndBadEmail` (422 `weak_password`, 422 `invalid_email`); `resendNeverRevealsAccounts` (unknown email → 200 `{}`, nothing sent).
- [ ] **Step 2:** Run `npm test -- register` — Expected: FAIL.
- [ ] **Step 3:** Implement handlers, `users.ts`, `teams.ts`, routes.
- [ ] **Step 4:** Run `npm test -- register` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(server): sign-up with email code and teams`

### Task 5: Login, refresh, logout, password reset

**Files:**
- Create: `server/lib/auth/login.ts`, `server/lib/auth/session.ts`, `server/lib/auth/reset.ts`
- Create routes: `server/app/v1/auth/login/route.ts`, `refresh/route.ts`, `logout/route.ts`, `password-reset/route.ts`, `password-reset/confirm/route.ts`
- Test: `server/test/login.test.ts`

**Interfaces:**
- Produces: `loginHandler`, `refreshHandler`, `logoutHandler`, `passwordResetHandler`, `passwordResetConfirmHandler` (all `(req, deps)`).
- Rules: login unknown email or wrong password → `invalid_credentials` 401 (run `verifyPassword` against a fixed dummy hash for unknown emails); correct password but unverified → send a `verify` code, respond 403 `email_not_verified`. Refresh `{ refresh_token }` → `{ access_token, refresh_token }`. Logout `{ refresh_token }` → 200 `{}` even for unknown tokens. Reset `{ email }` → 200 `{}` always; sends a `reset` code to an existing user. Confirm `{ email, code, new_password }` → checks code, `weak_password` if weak, sets the hash, sets `email_verified_at` if empty, revokes all refresh tokens, returns 200 `{ user, access_token, refresh_token }`.

- [ ] **Step 1: Write the failing tests:** `loginReturnsSession`; `wrongPasswordAndUnknownEmailLookTheSame` (both 401 `invalid_credentials`, same message); `unverifiedLoginSendsCode` (403 `email_not_verified` + one mail); `refreshAndLogout` (after logout the refresh token → 401); `resetChangesPasswordAndKillsSessions` (old password → 401, new → 200, a refresh token from before → 401); `resetForUnknownEmailIsSilent`; `authEndpointsAreRateLimited` (11th login from the same `x-forwarded-for` within a minute → 429 `rate_limited`).
- [ ] **Step 2:** Run `npm test -- login` — Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `npm test -- login` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(server): login, token refresh, logout, password reset`

### Task 6: Profile and account deletion

**Files:**
- Create: `server/lib/me.ts`, route `server/app/v1/me/route.ts` (`GET`, `PATCH`, `DELETE`)
- Test: `server/test/me.test.ts`

**Interfaces:**
- Consumes: `requireUser`, `userJson`.
- Produces: `getMe`, `patchMe` (`{ nickname?, faceit_url? }`; `faceit_url: null` clears it; invalid → 422 `validation_failed`), `deleteMe` (deletes the user; if they own a team, the team is deleted and its players become `soloPlayer` with `team_id = null`). Later plans add data tables with `on delete cascade` to `users`.

- [ ] **Step 1: Write the failing tests:** `meNeedsBearer` (no header → 401 `unauthorized`); `patchNicknameAndFaceit`; `deleteRemovesUserAndTokens` (afterwards login → 401, refresh → 401); `deletingStaffFreesPlayers` (player becomes `soloPlayer`, `team === null`).
- [ ] **Step 2:** Run `npm test -- me` — Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `cd server && npm test` (all) — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(server): profile and account deletion`

### Task 7: Core client — verification-aware auth

**Files:**
- Modify: `core/src/commonMain/kotlin/com/pulse/core/auth/Auth.kt` (`AuthService`, `LocalAuthService`)
- Modify: `core/src/commonMain/kotlin/com/pulse/core/api/Api.kt` (`ApiException.authError`, `ApiClient` refresh mutex, `RemoteAuthService`)
- Modify: `core/src/commonMain/kotlin/com/pulse/core/auth/SessionStore.kt`
- Modify: `core/src/commonMain/kotlin/com/pulse/core/model/User.kt` (`AuthError` new cases, `RegistrationForm.acceptedBetaTerms`, `AuthValidator.validateRegistration(form, requireBetaTerms: Boolean = false)`, `object BetaTerms { const val VERSION = "2026-10-26" }`)
- Test: `core/src/jvmTest/kotlin/com/pulse/core/storage/AccountsAndApiTests.kt` (new `RemoteSignUpTests` class)

**Interfaces:**
- Produces:
  ```kotlin
  sealed interface RegistrationOutcome {
      data class SignedIn(val user: User) : RegistrationOutcome
      data class VerificationSent(val email: String) : RegistrationOutcome
  }
  interface AuthService {
      suspend fun login(email: String, password: String): User          // EMAIL_NOT_VERIFIED as AuthException
      suspend fun register(form: RegistrationForm): RegistrationOutcome
      suspend fun verifyEmail(email: String, code: String): User = throw AuthException(AuthError.SERVER)
      suspend fun resendCode(email: String) {}
      suspend fun requestPasswordReset(email: String) {}
      suspend fun confirmPasswordReset(email: String, code: String, newPassword: String): User = throw AuthException(AuthError.SERVER)
      fun logout() {}
      fun updateProfile(user: User) {}
      fun deleteAccount(user: User) {}
  }
  ```
  `LocalAuthService.register` returns `SignedIn`. `RemoteAuthService`: demo emails go to `DemoAuth` locally for `login` (no request) and are `EMAIL_TAKEN` for `register`; `register` sends `accepted_beta_terms` and `beta_terms_version = BetaTerms.VERSION`; `logout()` posts `/auth/logout` on the background scope and clears tokens; maps codes `email_not_verified`, `invalid_code`, `code_expired`, `too_many_attempts`, `rate_limited` to the new `AuthError`s.
- Produces in `SessionStore`: `val requireBetaTerms: Boolean` (constructor param, default `false`); `val pendingVerification: StateFlow<String?>` (email awaiting a code); `val pendingReset: StateFlow<String?>`; `suspend fun verifyEmail(code: String)`; `suspend fun resendCode()`; `fun cancelVerification()`; `suspend fun requestPasswordReset(email: String)`; `suspend fun confirmPasswordReset(code: String, password: String, confirm: String)`; `fun cancelReset()`. A code that is not exactly 6 digits → `INVALID_CODE` without a request. `logout()` also calls `authService.logout()`. `ApiClient.refreshTokens` runs under a `Mutex`; a caller that waited re-reads the stored tokens and skips its own refresh if they changed.

- [ ] **Step 1: Write the failing tests** (MockEngine, style of `ApiClientTests`): `registerWaitsForCode` (201 → `pendingVerification == "nova@team.gg"`, `user == null`; then `verifyEmail("123456")` → user set, tokens stored); `unverifiedLoginOpensCodeStep` (403 `email_not_verified` → `pendingVerification` set, `errorMessage == null`); `codeErrorsUseSpecTexts` (`invalid_code` → «Неверный код», `too_many_attempts` → «Слишком много попыток. Запросите новый код»; `"12a"` → «Неверный код» with zero requests); `betaTermsRequiredWhenRemote` (form without `acceptedBetaTerms` → «Для продолжения необходимо принять условия бета-тестирования», no request; request body contains `"accepted_beta_terms":true` and `"beta_terms_version":"2026-10-26"`); `resetFlowSignsIn`; `demoSignInNeedsNoServer` (engine that fails every request; `switchToDemo(STAFF)` → user Coach, zero requests); `concurrentUnauthorizedRefreshesOnce` (two parallel `send` calls with an expired token → exactly one `/auth/refresh` request, both succeed); `logoutTellsServer` (one `/auth/logout` with the stored refresh token, tokens cleared).
- [ ] **Step 2:** Run `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :core:jvmTest --tests '*RemoteSignUpTests*'` — Expected: FAIL.
- [ ] **Step 3:** Implement; update existing tests that call `register` to unwrap `SignedIn`.
- [ ] **Step 4:** Run `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :core:allTests` — Expected: PASS (all core tests).
- [ ] **Step 5: Commit** — `feat(core): sign-up with email code, password reset, beta terms`

### Task 8: Backend per build

**Files:**
- Modify: `core/src/commonMain/kotlin/com/pulse/core/api/Api.kt` (remove `object AppConfig`; `ApiClient.isConfigured`; `SyncEngine.isEnabled` = `client.isConfigured`)
- Modify: `core/src/commonMain/kotlin/com/pulse/core/PulseCore.kt` (constructor param `backend: Backend = Backend.Local`)
- Modify: `androidApp/build.gradle.kts` (`buildConfigField("String", "API_BASE_URL", …)` from env `PULSE_API_BASE_URL` or `local.properties` `pulse.apiBaseUrl`, empty by default), `androidApp/src/main/java/com/pulse/android/PulseApplication.kt`
- Modify: `androidApp/src/test/java/com/pulse/android/ui/UiTestSupport.kt` (`testCore(role, faceitKey, backend = Backend.Local, http = HttpClient(OkHttp))`)
- Test: `core/src/jvmTest/kotlin/com/pulse/core/storage/PulseCoreTests.kt`

**Interfaces:**
- Produces: `PulseCore(..., backend: Backend = Backend.Local)`; `val PulseCore.isRemote: Boolean`; `SessionStore` built with `requireBetaTerms = isRemote`. In remote mode at start: a stored non-demo session with no tokens → `session.logout()`. Android: `Backend.Remote(BuildConfig.API_BASE_URL)` when non-empty, else `Backend.Local`.

- [ ] **Step 1: Write the failing tests:** `remoteBuildUsesServerAuth` (`PulseCore(backend = Remote("https://api.test/v1/"))` → `auth is RemoteAuthService`, `session.requireBetaTerms`); `localSessionDroppedInRemoteMode` (write `session.json` with a real local user, start remote core with no tokens → `session.user.value == null`; same with a demo user → kept); `notConfiguredMeansNoConnection` updated to drop `AppConfig`.
- [ ] **Step 2:** Run `./gradlew :core:jvmTest --tests '*PulseCoreTests*'` (JDK 17) — Expected: FAIL.
- [ ] **Step 3:** Implement core + Gradle + `PulseApplication`.
- [ ] **Step 4:** Run `./gradlew :core:allTests :androidApp:assembleDebug` — Expected: PASS / BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — `feat: choose local or server backend per build`

### Task 9: Android screens — beta terms, code, reset

**Files:**
- Modify: `androidApp/src/main/java/com/pulse/android/ui/auth/AuthScreen.kt` (third consent, remote team hints, «Забыли пароль?», switch to `VerifyEmailCard` while `pendingVerification != null`, `ResetCard`)
- Create: `androidApp/src/main/java/com/pulse/android/ui/auth/CodeCards.kt` (`VerifyEmailCard`, `ResetCard`)
- Modify: `androidApp/src/main/java/com/pulse/android/ui/auth/LegalTexts.kt` (`LegalDocument.BETA_TERMS`, its text; privacy text: data is stored on the Pulse server and usage analytics is collected — no health values)
- Create: `docs/beta-terms.md` (same text as `LegalTexts.betaTerms`; draft, lawyer review note)
- Modify: `androidApp/src/test/java/com/pulse/android/ui/AccessibilitySweepTest.kt`, `TextFitSweepTest.kt` (new cards)
- Test: `androidApp/src/test/java/com/pulse/android/ui/auth/RemoteAuthFlowTest.kt`

**Interfaces:**
- Consumes: `SessionStore` API from Task 7, `testCore(..., backend, http)` from Task 8.
- Beta terms text must say: closed testing, the app may contain errors and data may be lost or reset after the beta; data is stored on Pulse servers (Vercel, Neon) outside Russia and the tester consents to that; anonymous-by-content usage analytics (screens, time, actions — no health values) is collected to improve the app; the account and all data can be deleted at any time in the app or at `/delete-account`; participation is free and can stop at any time.
- Code field: `KeyboardType.NumberPassword`, max 6 digits, IME Done submits. Resend countdown via `LaunchedEffect` ticking a remembered deadline; survives rotation (`rememberSaveable`).

- [ ] **Step 1: Write the failing tests** (Robolectric, remote `testCore` over a MockEngine): `remoteRegistrationShowsBetaConsent` (third toggleable row «Я принимаю условия бета-тестирования» exists; absent in local mode); `registrationLeadsToCodeStep` (fill form, «Создать аккаунт» → «Подтвердите почту» and «Мы отправили 6-значный код на tester@example.com» shown); `codeStepSignsIn` (type `123456`, «Подтвердить» → session user set); `resendIsThrottled` («Отправить ещё раз через 1:00» shown right after sending); `forgotPasswordFlow` («Забыли пароль?» → «Получить код» → code + new password → signed in); `localModeHasNoForgotPassword`.
- [ ] **Step 2:** Run `./gradlew :androidApp:testDebugUnitTest --tests '*RemoteAuthFlowTest*'` (JDK 17) — Expected: FAIL.
- [ ] **Step 3:** Implement the cards and texts; add them to the accessibility and text-fit sweeps.
- [ ] **Step 4:** Run `./gradlew :androidApp:testDebugUnitTest :androidApp:lintDebug` — Expected: PASS, lint 0 errors / 0 warnings.
- [ ] **Step 5: Commit** — `feat(android): beta terms, email code and password reset screens`

### Task 10: Deploy the beta API and check it end to end

**Files:**
- Create: `server/vercel.json` only if the defaults need overriding (they should not).
- Modify: `docs/ANDROID.md` (section «Сервер»: how to run `server/` locally, env vars, how to build the beta APK with `PULSE_API_BASE_URL`), `docs/API.md` (new endpoints and codes from this plan).

**Owner steps (ask the owner; never do these for them):** connect Neon Postgres to the new Vercel project from the Marketplace; create a Resend account and an API key (until a domain is verified Resend only delivers to the account owner's own address — enough to test; testers need the domain). Pushing the branch to GitHub and creating the Vercel project are outward-facing: confirm with the owner before doing them.

- [ ] **Step 1:** With the owner's OK: push the branch; create Vercel project `pulse` (Root Directory `server`, framework Next.js) linked to `Tw1zzzzz/mobileCRM`, production branch `android`.
- [ ] **Step 2:** Set env vars in Vercel (Production + Preview): `AUTH_SECRET` (generate 48 random bytes, base64; never print it), `RESEND_API_KEY`, `MAIL_FROM`; `DATABASE_URL` comes from the Neon integration. Verify via the Vercel MCP that each name exists (do not decrypt values).
- [ ] **Step 3:** Deploy; check build logs show the migrations applied; `GET https://<deployment>/v1/health` → `{"success":true,"data":{"ok":true,"db":true},"error":null}`.
- [ ] **Step 4:** Build a debug APK with `PULSE_API_BASE_URL=https://<deployment>/v1/`, install on the `PulsePixel` emulator, and walk: register (solo) → code from the owner's inbox (or Vercel logs if `ConsoleMailer` on Preview) → signed in → logout → login → «Забыли пароль?» → new password works → delete account → login fails with «Неверный логин или пароль». Then staff registration shows the invite code in `/v1/me`, and a second account joins with it.
- [ ] **Step 5: Commit** docs — `docs: beta server setup and auth endpoints`
