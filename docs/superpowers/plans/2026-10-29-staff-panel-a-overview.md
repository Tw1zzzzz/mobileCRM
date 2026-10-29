# Staff panel A — team, sign-in, Overview and roster Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Staff of a team sign in at `/staff` on the Pulse site with their app account and see the real Overview and roster of their team, manage the team (two codes, remove members), and a helper can join the team as staff with the staff code.

**Architecture:** The panel is the `/staff` section of the existing Next.js app in `server/` (same Vercel project and database). Pages are React Server Components that call `server/lib/**` functions directly (no HTTP hop); changes go through Server Actions (Next checks `Origin` against the host, which covers CSRF). A web session is the plan-1 refresh token stored in an `HttpOnly` cookie and looked up (not rotated) on each page load. Player data comes from plan 2's `sync_documents` through one access layer that enforces team membership and the spec's privacy filters. Staff metrics are computed in TypeScript (`server/lib/staff/metrics.ts`) and the same formulas get a Kotlin twin in `core` (`StaffMetrics.kt`); both are checked against one shared fixture file so the numbers cannot drift.

**Tech Stack:** Next.js 16 App Router (Server Components, Server Actions), TypeScript, postgres.js, zod, vitest; CSS Modules (no CSS framework); hand-written SVG charts as in the design boards; Kotlin Multiplatform `core` (kotlinx.serialization), Jetpack Compose for the registration change.

**Spec:** `docs/superpowers/specs/2026-10-28-staff-panel-design.md` (this plan = «Вход на сайт», «Команда и стафф», «Откуда берутся цифры», tabs «Обзор» and «Игроки» without the player card; the card, «Статистика» and Faceit are plan B; «Игровая статистика» plan C; shared calendar, lineups and food comments plan D). Builds on `docs/superpowers/specs/2026-10-26-beta-server-design.md` and plans 1–2.

## Global Constraints

- Design source of truth: `docs/superpowers/specs/2026-10-28-staff-panel-design-boards/TeamOverview.dc.html` (Обзор) and `Players.dc.html` (roster part: «Игроки», «Состав и карточки», the roster list and the readiness legend). Layout, colours, fonts, spacing, copy and thresholds come from these files verbatim; demo names, «Atlant Esports», demo times and generated numbers are replaced by real data or empty states.
- Panel copy (verbatim): login title «Кабинет стаффа»; fields «Email», «Пароль»; button «Войти»; link «Забыли пароль?»; non-staff after sign-in «Кабинет доступен только стаффу команды»; empty metric «нет данных»; tabs not built yet show «Скоро» and are not links.
- Readiness 0–100 (owner's decision, from `Players.dc.html`): `round(clamp((sleepHours − 4.5) / 4 × 30 + mood × 3 + energy × 2.5 + cog × 0.3, 0, 100))` over the last day; statuses «Готов» ≥ 70, «Под вопросом» 55–69, «Риск» < 55. Missing inputs: the sum of the available terms is scaled by `115 / (sum of the available terms' maxima)` with maxima sleep 30, mood 30, energy 25, cog 30, then clamped — so with all four present the result is exactly the design formula; no input at all → «нет данных».
- Pills from `TeamOverview.dc.html`: mood avg «Отлично» ≥ 6.8, «Средне» ≥ 5.2, else «Плохо»; sleep avg «Отлично» ≥ 7.3 h, «Средне» ≥ 6.6 h; every weekly metric's ok/mid thresholds exactly as in that file's `metric(...)` calls.
- Data sources (spec «Откуда берутся цифры»): sleep hours — `sleep.sessions` (night ending that day); mood, energy — `wellbeing.mood` (day average); cognitive index — `cognitive.results` scores of that day (average); calories — `nutrition.meals`. Days are calendar days in `Europe/Moscow` for the beta (one setting, `STAFF_TIME_ZONE`, default `Europe/Moscow`).
- Privacy (spec of the server, «Команды и стафф»): staff read only players with `team_id` = their team and role `teamPlayer`; `body-profile` is never read; `lineups` only `visibility: team`; `player-survey` only scores, never answers. Every access goes through `server/lib/staff/access.ts`.
- Web session cookie `pulse_staff`: `HttpOnly`, `Secure` (except `NODE_ENV=development`), `SameSite=Lax`, `Path=/`, `Max-Age` 30 days; value = a refresh token issued by `issueSession`; looked up by SHA-256 like plan 1; revoked on «Выйти». Sign-in shares the `auth:<ip>` 10/min limit.
- Team codes: player code (existing `invite_code`) and staff code (new `staff_code`), both 6 chars from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, unique across both columns; rotating a code makes the old one stop working at once.
- No invented numbers anywhere: a block without data shows «нет данных».

## Review Focus

- A staff member of team A requests team B's roster, a player id from team B, or a solo player's id → nothing about them is returned (404-equivalent, no names). Test `staffCannotReadOtherTeams` (Task 3).
- A player is removed from the team while the coach has the Overview open → after the next page load the player is gone from every block and their data is no longer readable. Test `removedPlayerDisappears` (Task 3).
- A player has sleep but no mood and no tests → readiness is computed from sleep alone (scaled), not shown as 0 and not invented. Test `readinessFromSleepOnly` (Task 4), mirrored in Kotlin.
- The cookie of a staff member whose account was deleted or whose sessions were revoked (password reset) → the next page load goes to `/staff/login`. Test `revokedSessionRedirects` (Task 5).
- The last staff member tries to remove themselves → refused with a clear message; the team survives. Test `lastStaffCannotLeave` (Task 1).

---

### Task 1: Server — team with two codes and several staff

**Files:**
- Create: `server/db/migrations/006_team_staff.sql`, `server/lib/team/members.ts`, `server/test/team.test.ts`
- Modify: `server/lib/teams.ts` (code generation unique across both columns, `findTeamByStaffCode`), `server/lib/auth/register.ts` (staff joins with `staff_code`), `server/lib/me.ts` (`deleteMe` with several staff), `server/lib/users.ts` (`userJson`: staff see both codes)

**Interfaces:**
- Produces: column `teams.staff_code text unique not null` (backfilled for existing teams); `rotateCode(deps, teamId, kind: "player" | "staff"): Promise<string>`; `removeMember(deps, teamId, actorId, memberId): Promise<void>` (throws `ApiError("validation_failed", "The last staff member cannot leave the team.", 422)` for the last staff; member of another team → `ApiError("not_found", …, 404)`); `teamMembers(deps, teamId): Promise<{ id, nickname, role, joined_at }[]>`.
- Registration: role `staff` takes exactly one of `team_name` (create, as now) or `staff_code` (join that team as staff); both or neither → 422 `validation_failed`; unknown `staff_code` → 404 `team_not_found`. `userJson` for staff adds `team.staff_code` next to `team.invite_code`.
- Removal: a removed player becomes `soloPlayer` with `team_id` null; a removed staff member becomes `soloPlayer` too (Ruling in the spec: «убранный … становится соло»).
- `deleteMe` for a staff member: other staff remain → team stays, `owner_id` moves to the longest-standing other staff; last staff → current behaviour (team deleted, players solo).

- [ ] **Step 1: Write failing tests** in `server/test/team.test.ts`: `staffJoinsWithStaffCode`, `staffNeedsExactlyOneOfNameOrCode`, `unknownStaffCodeIsTeamNotFound`, `playerCodeDoesNotJoinAsStaff` (a player code used as `staff_code` → 404), `rotateCodeInvalidatesOld` (old code → 404 on register, new works), `codesUniqueAcrossBothColumns` (generator never returns an existing code of either kind — seed a collision with a stubbed random source), `removePlayerMakesSolo`, `removeStaffMakesSolo`, `lastStaffCannotLeave`, `cannotRemoveMemberOfOtherTeam`, `deleteStaffKeepsTeamWhenOthersRemain`, `deleteLastStaffDeletesTeam`, `meShowsBothCodesToStaff` (and no codes to players).
- [ ] **Step 2:** `cd server && npm test -- team` → FAIL.
- [ ] **Step 3: Implement.** Backfill in the migration with distinct codes (`md5`-based is fine as long as the alphabet and length match; or generate in `migrate.ts` — builder's choice, but every existing team ends with a valid unique code).
- [ ] **Step 4:** `npm test`, `npx tsc --noEmit` → PASS.
- [ ] **Step 5: Commit** `feat(server): teams with a staff code and several staff`.

### Task 2: App — join a team as staff

**Files:**
- Modify: `core/src/commonMain/kotlin/com/pulse/core/model/User.kt` (`RegistrationForm.staffCode`, `Team.staffCode`), `core/src/commonMain/kotlin/com/pulse/core/api/Api.kt` (`RegisterRequest.staff_code`, `UserDto` team codes), the auth registration screen in `androidApp/src/main/java/com/pulse/android/ui/auth/` (staff: segmented «Создать команду» / «Вступить по коду стаффа»), the staff profile/team screen that shows the team code (show both codes, labelled «Код для игроков» and «Код для стаффа»)
- Test: `core/src/jvmTest/.../RemoteAuthFlowTest` or a new core test; `androidApp/src/test/.../StaffJoinTest.kt`

**Interfaces:**
- Consumes: Task 1 wire (`staff_code` in register; `team.staff_code` in `/me` for staff).
- Copy (verbatim): segment «Создать команду» / «Вступить по коду стаффа»; field «Код стаффа»; hint «Код стаффа даёт тренер команды.»; profile labels «Код для игроков», «Код для стаффа».

- [ ] **Step 1: Write failing tests:** `staffRegistrationSendsStaffCode` (MockEngine body has `staff_code`, no `team_name`), `staffJoinUnknownCodeShowsTeamNotFound` (existing `TEAM_NOT_FOUND` text), Android `staffJoinModeShowsCodeField`, `profileShowsBothCodes`.
- [ ] **Step 2:** `./gradlew :core:jvmTest --tests '*Staff*'` and `:androidApp:testDebugUnitTest --tests '*StaffJoin*'` → FAIL.
- [ ] **Step 3: Implement.** Local (non-remote) builds keep today's behaviour.
- [ ] **Step 4:** `./gradlew :core:allTests :androidApp:testDebugUnitTest :androidApp:lintDebug` → PASS.
- [ ] **Step 5: Commit** `feat(android): staff can join a team with the staff code`.

### Task 3: Server — staff access layer over synced player data

**Files:**
- Create: `server/lib/staff/access.ts`, `server/test/staff-access.test.ts`

**Interfaces:**
- Consumes: plan 2 `sync_documents`; Task 1 `teamMembers`.
- Produces:
  ```ts
  type StaffContext = { userId: string; teamId: string; teamName: string };
  requireStaff(deps, userId): Promise<StaffContext>            // 403-equivalent ApiError("unauthorized"…) if not staff with a team
  teamPlayers(deps, ctx): Promise<{ id: string; nickname: string; lastActivity: Date | null }[]>  // teamPlayer members only
  playerDocuments(deps, ctx, playerId, collections: Collection[]): Promise<Partial<Record<Collection, unknown>>>
  ```
  `Collection` excludes `body-profile` at the type level; `lineups` returns only `visibility: "team"` items; `player-survey` returns `{ mindset scores }` only (no `answers`); `lastActivity` = latest `sync_documents.updated_at` of that player. A player id not in the caller's team → `ApiError("not_found", …, 404)`.

- [ ] **Step 1: Write failing tests:** `staffCannotReadOtherTeams` (other team's player, solo player, staff member as "player" → 404), `bodyProfileNeverReturned`, `lineupsOnlyTeamVisibility`, `surveyWithoutAnswers`, `removedPlayerDisappears` (after `removeMember` the id → 404 and is gone from `teamPlayers`), `lastActivityFromSyncDocuments`, `nonStaffRejected`.
- [ ] **Step 2:** `npm test -- staff-access` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `npm test`, `npx tsc --noEmit` → PASS.
- [ ] **Step 5: Commit** `feat(server): staff read their players' synced data through one access layer`.

### Task 4: Staff metrics — TypeScript and Kotlin, one fixture

**Files:**
- Create: `server/lib/staff/metrics.ts`, `server/test/staff-metrics.test.ts`, `server/test/fixtures/staff-metrics.json`, `core/src/commonMain/kotlin/com/pulse/core/feature/StaffMetrics.kt`, `core/src/jvmTest/kotlin/com/pulse/core/feature/StaffMetricsParityTest.kt`

**Interfaces:**
- Consumes: documents as returned by Task 3 (camelCase app JSON).
- Produces (TS; Kotlin mirrors names in camelCase on `object StaffMetrics`):
  ```ts
  type Day = string; // YYYY-MM-DD in STAFF_TIME_ZONE
  dailyInputs(docs, day, tz): { sleepHours?: number; mood?: number; energy?: number; cog?: number; kcal?: number }
  readiness(inputs): number | null                 // Global Constraints formula
  readinessStatus(value): "ready" | "doubt" | "risk"
  moodCategories(entries): { name: string; range: string; count: number }[]   // categories, names and ranges from TeamOverview.dc.html CAT
  pill(value, ok, mid): "good" | "mid" | "bad"
  teamOverview(playersDocs, today, tz, who: "team" | playerId): OverviewData // everything the Overview renders
  ```
- `server/test/fixtures/staff-metrics.json`: ≥ 12 cases (all inputs, each single input missing, only sleep, nothing, clamping above 100 and below 0, mood categories edges, pill edges). The TS test and the Kotlin jvmTest read the same file (Kotlin by relative path from the repo root) and assert identical outputs.

- [ ] **Step 1: Write the fixture and failing tests** (`readinessMatchesDesignFormula`, `readinessFromSleepOnly`, `readinessNothingIsNull`, `readinessClamps`, `moodCategoriesFromBoard`, `pillThresholdsFromBoard`, `dailyInputsUsesStaffTimeZone` (a session ending 23:30 UTC is the next day in Moscow), `teamOverviewSkipsPlayersWithoutData`), and `StaffMetricsParityTest` over the fixture.
- [ ] **Step 2:** `npm test -- staff-metrics` and `./gradlew :core:jvmTest --tests '*StaffMetricsParity*'` → FAIL.
- [ ] **Step 3: Implement both.** Kotlin is pure (no stores); plan B wires the Android staff screens to it.
- [ ] **Step 4:** both commands → PASS; `npm test`, `./gradlew :core:allTests` → PASS.
- [ ] **Step 5: Commit** `feat: staff metrics with one fixture for the panel and the app`.

### Task 5: Panel shell and sign-in

**Files:**
- Create: `server/app/staff/layout.tsx`, `server/app/staff/login/page.tsx`, `server/app/staff/reset/page.tsx`, `server/app/staff/actions.ts` (sign-in, reset, sign-out Server Actions), `server/lib/staff/session.ts`, `server/app/staff/staff.module.css` (design tokens and shell from the boards), `server/components/staff/{Sidebar,Header}.tsx`, `server/test/staff-session.test.ts`
- Modify: `server/app/staff/page.tsx` → redirect to `/staff/overview`

**Interfaces:**
- Consumes: plan 1 `loginHandler` logic (extract a shared `authenticate(deps, email, password)` if needed), `issueSession`, `revokeRefresh`, reset handlers; Task 3 `requireStaff`.
- Produces: `staffSession(deps, cookieValue): Promise<StaffContext | null>`; layout redirects to `/staff/login` when null; non-staff signed in → page with «Кабинет доступен только стаффу команды» and «Выйти».
- Sidebar items in the order of the design's «Меню Staff»: Обзор, Календарь, Команда, Игроки, Раскидки и скрипты, Статистика, Игровая статистика. Live in this plan: Обзор (`/staff/overview`) and Игроки (`/staff/players`); «Команда» opens the team management block on `/staff/players`; the rest show «Скоро». Header: team name and «N игроков»; the design's demo event chips are not shown until plan D.
- Email not verified → «Подтвердите почту» (plan-1 text) with the hint «Код пришёл на почту — введите его в приложении.».
- Phone width: the sidebar collapses into a top menu (design boards are 1440 wide; follow their breakpoints if present, else ≤ 900 px).

- [ ] **Step 1: Write failing tests** (`staff-session.test.ts` against the lib, not the browser): `signInSetsCookieValue` (returns a token whose hash exists), `wrongPasswordSameErrorAsApp`, `nonStaffGetsNoContext`, `revokedSessionRedirects` (revoked / expired / deleted user → null), `signOutRevokes`, `signInRateLimited` (shares `auth:<ip>`).
- [ ] **Step 2:** `npm test -- staff-session` → FAIL.
- [ ] **Step 3: Implement** pages and actions; cookie flags exactly as Global Constraints.
- [ ] **Step 4:** `npm test`, `npx tsc --noEmit`, `npx next build` → PASS (build lists `/staff/*` routes).
- [ ] **Step 5: Commit** `feat(server): staff panel sign-in and shell`.

### Task 6: Обзор

**Files:**
- Create: `server/app/staff/overview/page.tsx`, `server/components/staff/overview/*.tsx` (one component per board block), `server/app/staff/overview/overview.module.css`, `server/test/staff-overview.test.tsx` (render with React's server renderer from fixtures)

**Interfaces:**
- Consumes: Task 3 (`teamPlayers`, `playerDocuments` for `sleep`, `wellbeing`, `cognitive`, `nutrition`), Task 4 (`teamOverview`).
- Blocks as in `TeamOverview.dc.html`: team readiness today (big number + «готовность»), «Недельный обзор» with the metric chips «Состояние», «Сон», «Питание» (the «Игра» chip is hidden until plan C) and the «Вся команда» / player selector, «Настроение» with categories and pill, «Сон и энергия» 7 days with «Средний сон», «Энергия сегодня», «Меньше всего сна», the weekly metric cards with value, delta and pill. Filters live in the URL (`?who=`, `?show=`) so the page stays a Server Component.
- Empty states: no players yet → the board's layout with «В команде пока нет игроков» and the player code; a block without data → «нет данных».

- [ ] **Step 1: Write failing tests:** render from fixtures — `overviewShowsTeamReadiness`, `overviewWhoFilterSwitchesToPlayer`, `overviewEmptyTeamShowsInviteCode`, `blockWithoutDataSaysNoData`, `noDemoNamesInMarkup` (none of «Илья Воронов», «Atlant Esports», «Тренировка 12:00» appear).
- [ ] **Step 2:** `npm test -- staff-overview` → FAIL.
- [ ] **Step 3: Implement** markup and CSS ported from the board (same structure, class names may differ).
- [ ] **Step 4:** `npm test`, `npx tsc --noEmit`, `npx next build` → PASS.
- [ ] **Step 5: Commit** `feat(server): staff Overview on real data`.

### Task 7: Игроки — roster and team management

**Files:**
- Create: `server/app/staff/players/page.tsx`, `server/components/staff/players/{Roster,TeamManagement}.tsx`, `server/app/staff/players/players.module.css`, `server/test/staff-players.test.tsx`
- Modify: `server/app/staff/actions.ts` (rotate code, remove member)

**Interfaces:**
- Consumes: Tasks 1, 3, 4.
- Roster as in `Players.dc.html`: header «Игроки», segments «Состав и карточки» / «Питание команды» (the second shows «Скоро» until plan D), «Готовность сегодня» summary, one row per player with readiness and status, nickname, «Сон», «Настр.», «Когн.», last activity, and the legend text from the board verbatim. A row is not a link yet (player card = plan B).
- Team management block («Команда»): both codes with copy buttons, «Новый код» per code (confirm «Старый код перестанет работать. Продолжить?»), staff list and player list with «Убрать из команды» (confirm «{nickname} станет соло-игроком и пропадёт из панели. Продолжить?»); the last-staff error text «Нельзя убрать последнего стаффа команды».

- [ ] **Step 1: Write failing tests:** `rosterRowsFromRealData`, `rosterLegendVerbatim`, `rotateActionChangesCode`, `removeActionRemovesPlayer`, `removeLastStaffShowsError`, `actionsRequireStaffOfThatTeam` (an action with another team's member id fails).
- [ ] **Step 2:** `npm test -- staff-players` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `npm test`, `npx tsc --noEmit`, `npx next build` → PASS.
- [ ] **Step 5: Commit** `feat(server): staff roster and team management`.

### Task 8: Deploy and check end to end

**Files:**
- Modify: `docs/API.md` (register `staff_code`, `/me` codes), `docs/ANDROID.md` (staff join), a new short `docs/STAFF_PANEL.md` (how staff sign in, what is live, what comes in plans B–D)

- [ ] **Step 1:** With the owner's OK: push, create a production deployment of the pushed commit through the Vercel MCP (`target: "production"`), check `READY`, `/v1/health` ok, `/staff/login` renders.
- [ ] **Step 2:** On the emulator (debug build against the beta server): the owner registers a staff account with a new team; a second account joins as a player with the player code and adds a sleep night and a mood entry; a third joins as staff with the staff code. In the browser pane: both staff accounts sign in at `/staff`, see the player in Overview and roster with readiness from the real entries; rotate the player code; remove and re-check.
- [ ] **Step 3: Commit** docs — `docs: staff panel A`.
