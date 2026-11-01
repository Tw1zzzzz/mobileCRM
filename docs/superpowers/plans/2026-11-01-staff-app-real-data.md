# Staff in the app on real data Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A real staff account in the Android app sees its own team's players and their stats — «Сегодня», «Игроки» (roster and player card), «Аналитика» (team, correlations, food) and the weekly team report — computed from the players' synced data with the same formulas as the website. Demo accounts keep the demo team.

**Architecture:** The server gets three bearer-token JSON endpoints under `/v1/staff/` that expose the roster, a player's filtered documents and meal photos through the existing staff access layer (`server/lib/staff/access.ts`, privacy filters included). `core` gets a `StaffTeam` repository that downloads the team's documents, keeps the last copy on disk for offline use, and turns them into the shapes the existing staff screens draw — using only the Kotlin twins that already match the website (`StaffMetrics`, `StaffSeries`, `StaffStatistics`). The Android screens keep their layout; they switch their data source from the demo generators to `StaffTeam` when the account is real. Faceit matches come from the app's built-in Faceit key, as the player's own screens already do.

**Tech Stack:** Next.js route handlers + postgres.js + vitest (server); Kotlin Multiplatform `core` with Ktor (`MockEngine` in tests) and kotlinx.serialization `JsonElement`; Jetpack Compose + Robolectric.

**Spec:** `docs/superpowers/specs/2026-10-26-beta-server-design.md` («Команды и стафф — реальные данные»: staff screens in the app on real data, metrics from real data, «нет данных» instead of invented values) and `docs/superpowers/specs/2026-10-28-staff-panel-design.md` («Откуда берутся цифры», owner's answers: one readiness formula 0–100 everywhere, the app's staff screens move to it; games entered on the site are not shown in the app). Owner, 2026-11-01: the app's «Игроки» and «Аналитика» must show the real players; the app never sends staff to the website.

## Global Constraints

- One source of numbers: every value on a staff screen for a real account comes from `StaffMetrics` / `StaffSeries` / `StaffStatistics` (or plain counts/averages of the documents with the same day rules). No formula is re-implemented in Android code. Readiness is 0–100 with statuses «Готов» ≥ 70, «Под вопросом» 55–69, «Риск» < 55 (the old 0–10 demo scale stays only for demo accounts).
- Days: `StaffMetrics.dayOf(instant, tz)` with `tz` = the phone's current time zone (`TimeZone.currentSystemDefault().id`); mood days as `StaffMetrics` already does.
- Privacy is the server's job and is unchanged: only `teamPlayer` members of the caller's team; `body-profile` never; `lineups` team-visible only; `player-survey` scores only. The app never asks for anything else.
- No invented values: a metric without data shows «нет данных»; a player without any data still appears in the roster with «нет данных» in each column.
- No mention of the website anywhere in the app (no «на сайте», «кабинет», «/staff»).
- Demo staff accounts: unchanged, demo generators as today.
- Offline: the last downloaded team copy is shown with «Обновлено {время}» under the screen header; nothing blocks the UI; pull-to-refresh / screen open retries.
- Wire: snake_case envelope as `docs/API.md`; documents inside are the app's own JSON (camelCase), exactly as stored.
- Limits: `/v1/staff/*` 120 requests/min per user (`rateLimit(deps, "staff:" + userId, 120, 60_000)`); photos 600/min under the existing `photos:` bucket.

## Review Focus

- A player removed from the team while the coach's phone still has the cached copy → the next successful refresh drops them from every screen and from the disk cache. Test `removedPlayerLeavesCacheOnRefresh` (Task 2).
- A staff account switches to another account on the same phone → the next account never sees the previous team's cache. Test `cacheIsPerAccount` (Task 2).
- One player's documents fail to download (500) while others succeed → the roster still shows everyone; that player shows «нет данных» and the screen shows the stale-data line, not an error page. Test `onePlayerFailureKeepsTheRest` (Task 2).
- A real staff account opens a player card restored from a previous demo session (route with a demo player id) → «Игрок не найден», no demo data. Test `demoIdOnRealAccountIsNotFound` (Task 5).
- Readiness on the phone equals the website's for the same documents and day. Test `appReadinessMatchesFixture` (Task 3), using `server/test/fixtures/staff-metrics.json`.

---

### Task 1: Server — staff JSON endpoints for the app

**Files:**
- Create: `server/lib/staff/api.ts`, `server/app/v1/staff/team/route.ts`, `server/app/v1/staff/players/[playerId]/documents/route.ts`, `server/app/v1/staff/players/[playerId]/photos/[mealId]/route.ts`, `server/test/staff-api.test.ts`
- Modify: `docs/API.md` (new section «Стафф в приложении»)

**Interfaces:**
- Consumes: `requireUser` (bearer), `requireStaff`, `teamPlayers`, `rosterPlayer`, `playerDocuments` (`server/lib/staff/access.ts`), staff photo reading (`server/lib/staff/photos.ts`), `rateLimit`.
- Wire:
  - `GET /v1/staff/team` → `{ team: { name, invite_code }, players: [{ id, nickname, last_activity | null }] }`
  - `GET /v1/staff/players/{id}/documents?collections=sleep,wellbeing,…` → `{ documents: { <collection>: <document> } }` (only stored ones; unknown or forbidden collection names → 422 `validation_failed`; `body-profile` always forbidden)
  - `GET /v1/staff/players/{id}/photos/{meal_id}` → raw JPEG like `/v1/photos`, or 404 `not_found`
  - Not staff with a team → 403 `forbidden`; a player id outside the team → 404 `not_found`.

- [ ] **Step 1: Write failing tests** `teamListsOnlyTeamPlayers`, `documentsAreFiltered` (lineups team-only, survey without answers), `bodyProfileRefused`, `otherTeamPlayerIsNotFound`, `nonStaffIsForbidden`, `photoOfTeamPlayer`, `photoOfOtherTeamIsNotFound`, `staffBucketLimits`.
- [ ] **Step 2:** `cd server && npm test -- staff-api` → FAIL.
- [ ] **Step 3: Implement** (handlers thin; all checks in the access layer).
- [ ] **Step 4:** `npm test`, `npx tsc --noEmit` → PASS.
- [ ] **Step 5: Commit** `feat(server): staff endpoints for the app`.

### Task 2: Core — `StaffTeam` repository with an offline copy

**Files:**
- Create: `core/src/commonMain/kotlin/com/pulse/core/staff/StaffTeam.kt`, `core/src/jvmTest/kotlin/com/pulse/core/staff/StaffTeamTest.kt`
- Modify: `core/src/commonMain/kotlin/com/pulse/core/PulseCore.kt` (expose `val staffTeam: StaffTeam`, refresh on activation of a real staff account, clear on account switch), `core/.../api/Api.kt` only if a raw-bytes GET helper is missing

**Interfaces:**
- Produces:
  ```kotlin
  data class StaffPlayer(val id: String, val nickname: String, val lastActivity: Instant?)
  data class TeamSnapshot(val teamName: String, val inviteCode: String?, val players: List<StaffPlayer>,
                          val documents: Map<String, Map<String, JsonElement>>, // playerId → collection → document
                          val fetchedAt: Instant, val missing: Set<String>)    // players whose documents failed
  sealed interface StaffTeamState { data object Idle; data object Loading; data class Ready(val team: TeamSnapshot, val stale: Boolean); data object Failed }
  class StaffTeam {
      val state: StateFlow<StaffTeamState>
      suspend fun refresh()                       // never throws for network/server errors
      suspend fun photo(playerId: String, mealId: String): ByteArray?
      fun players(): List<StaffMetrics.PlayerDocuments> // for the Kotlin twins
  }
  ```
- Collections requested: `sleep`, `wellbeing`, `cognitive`, `nutrition`, `fitness-state`, `screen-time`, `faceit`, `player-survey`.
- Disk copy: `staff-team.json` in the account folder, written directly through `scope.files` (never marked changed, never synced — add it to the never-sent list next to `sync-photos.json`). Loaded at activation so screens have data offline; replaced on every successful refresh (removed players disappear).
- Refresh: roster first, then each player's documents (sequential or bounded parallel, at most 3 at a time); a failed player goes into `missing` and keeps their previous documents if the cache had them.
- Only for a real staff account with a server; demo and local builds keep `Idle` and never call the server.

- [ ] **Step 1: Write failing tests:** `refreshDownloadsRosterAndDocuments`, `onePlayerFailureKeepsTheRest`, `offlineShowsCachedCopyAsStale`, `removedPlayerLeavesCacheOnRefresh`, `cacheIsPerAccount`, `demoAccountNeverCallsServer`, `cacheFileNeverSynced`.
- [ ] **Step 2:** `./gradlew :core:jvmTest --tests '*StaffTeamTest*'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `./gradlew :core:allTests` → PASS.
- [ ] **Step 5: Commit** `feat(core): staff team repository with an offline copy`.

### Task 3: Core — what the staff screens draw, from real data

**Files:**
- Create: `core/src/commonMain/kotlin/com/pulse/core/staff/StaffViews.kt`, `core/src/jvmTest/kotlin/com/pulse/core/staff/StaffViewsTest.kt`

**Interfaces:**
- Consumes: `TeamSnapshot` (Task 2), `StaffMetrics`, `StaffSeries`, `StaffStatistics`.
- Produces pure functions (no Compose), one per screen area, returning plain data the screens render:
  - `roster(team, today, tz)`: per player — readiness (Int?) + status, sleep hours last night, mood avg today, cognitive index, last activity; sorted by readiness desc, nulls last.
  - `todayOverview(team, today, tz)`: team readiness (mean of known), players count, «Требуют внимания» list (readiness < 55, or sleep < 6.6 h last night, or no data for 3+ days — the reason text per player), using `StaffMetrics.teamOverview`.
  - `playerCard(team, playerId, today, tz, periodDays)`: tests (cognitive + game tests per day), sleep sessions table, load (fitness-state), food rows (with meal ids for photos), wheel of balance, archetype (mindset scores), Faceit `playerId` for the game section.
  - `teamAnalytics(team, today, tz, periodDays)`: KPIs, readiness per player, team readiness trend (daily mean/min/max), table rows.
  - `teamCorrelations(team, matches, today, tz, periodDays)`: matrix and strongest links via `StaffStatistics` (state factors vs Faceit match metrics; `matches` supplied by the caller).
  - `teamFood(team, today, tz, days)`: meals of all players, newest first, with author, kcal/БЖУ, photo ref.
  - `weeklyTeam(team, week, tz)`: the weekly team report rows (readiness, sleep, mood, risks) for `WeeklyReportScreen`.
- Every number with no data is `null`.

- [ ] **Step 1: Write failing tests** for each function from small hand-made documents, plus `appReadinessMatchesFixture` (iterate `server/test/fixtures/staff-metrics.json` through `roster`), `noDataIsNull`, `rosterKeepsPlayersWithoutData`, `attentionReasons`.
- [ ] **Step 2:** `./gradlew :core:jvmTest --tests '*StaffViewsTest*'` → FAIL.
- [ ] **Step 3: Implement** (no new formulas — compose the twins).
- [ ] **Step 4:** `./gradlew :core:allTests` → PASS.
- [ ] **Step 5: Commit** `feat(core): staff screen data from the real team`.

### Task 4: Android — «Сегодня», roster and profile on real data

**Files:**
- Modify: `androidApp/src/main/java/com/pulse/android/ui/staff/StaffTabs.kt`, `.../ui/staff/PlayersScreens.kt` (roster), `.../ui/staff/TeamDemoGate.kt`, `.../ui/profile/ProfileScreen.kt` (players stat), `.../ui/auth/AuthScreen.kt` (`teamHint` without the server/cabinet wording: staff «После регистрации вы получите код команды — отправьте его игрокам.»)
- Test: `androidApp/src/test/java/com/pulse/android/ui/staff/RealStaffAccountTest.kt` (extend)

**Interfaces:**
- Consumes: `core.staffTeam.state`, `refresh()`, Task 3 `todayOverview`, `roster`.
- Real staff «Сегодня»: the same ring/stat/attention layout as the demo, readiness 0–100 (ring fraction = value / 100); empty team → the existing «В команде пока нет игроков» card with the player code; loading first time → the screen's skeleton/progress; offline → last copy + «Обновлено {Formatters time}».
- Roster: same rows as the demo (avatar, nickname, readiness + status pill, «Сон», «Настр.», «Когн.», last activity), search kept; a row opens `Route.PlayerDetail(id)`.
- Players header: «{team} · {N} в составе» with the real N. Profile «игроков» = real N.
- Refresh triggers: account activation (Task 2), opening the «Игроки» or «Сегодня» tab if the copy is older than 5 minutes, pull-to-refresh where the screen already scrolls.

- [ ] **Step 1: Write failing tests:** `realTodayShowsTeamReadiness`, `realRosterRowsFromServer`, `realRosterNoDemoNames`, `emptyTeamShowsPlayerCode`, `offlineShowsUpdatedAt`, `profileCountsRealPlayers`, demo tests unchanged.
- [ ] **Step 2:** `./gradlew :androidApp:testDebugUnitTest --tests '*RealStaffAccount*'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `./gradlew :androidApp:testDebugUnitTest :androidApp:lintDebug` → PASS.
- [ ] **Step 5: Commit** `feat(android): staff Today and roster on the real team`.

### Task 5: Android — player card on real data

**Files:**
- Modify: `androidApp/src/main/java/com/pulse/android/ui/staff/PlayersScreens.kt` (`PlayerDetailScreen` and its cards)
- Test: `androidApp/src/test/java/com/pulse/android/ui/staff/RealPlayerCardTest.kt`

**Interfaces:**
- Consumes: Task 3 `playerCard`, `core.staffTeam.photo`, the app's Faceit client (`GameStore`'s `FaceitClient` with the built-in key) for the player's matches by `faceit.playerId` (same period filter as the card).
- Same sections as the demo card, fed from `playerCard`; filters «Тип теста», «Источник игр» keep their options but «Источник игр» offers only «Все» / «Faceit» (pracks are not in the app); meal photos load lazily through `staffTeam.photo`.
- Unknown id (incl. a demo id on a real account) → «Игрок не найден».

- [ ] **Step 1: Write failing tests:** `cardShowsRealSleepAndTests`, `cardFoodPhotoLoadsFromServer`, `cardGameUsesFaceitMatches` (MockEngine Faceit), `cardWithoutFaceitSaysNoData`, `demoIdOnRealAccountIsNotFound`.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `./gradlew :androidApp:testDebugUnitTest :androidApp:lintDebug` → PASS.
- [ ] **Step 5: Commit** `feat(android): staff player card on real data`.

### Task 6: Android — «Аналитика» (team, correlations, food) and the weekly team report

**Files:**
- Modify: `androidApp/src/main/java/com/pulse/android/ui/staff/TeamAnalyticsScreens.kt`, `androidApp/src/main/java/com/pulse/android/ui/weekly/WeeklyReportScreen.kt`, `androidApp/src/main/java/com/pulse/android/ui/today/TodayScreen.kt` (`WeeklySummaryCard` subtitle from real data)
- Test: `androidApp/src/test/java/com/pulse/android/ui/staff/RealTeamAnalyticsTest.kt`

**Interfaces:**
- Consumes: Task 3 `teamAnalytics`, `teamCorrelations` (matches for all players via the Faceit client, bounded to 3 parallel requests, cached for the screen's life), `teamFood`, `weeklyTeam`; `staffTeam.photo`.
- Same layouts as the demo; readiness 0–100; the ELO chart uses `faceit.snapshots` of each player; «Питание игроков» uses real meals and photos.

- [ ] **Step 1: Write failing tests:** `realAnalyticsKpis`, `realCorrelationsFromStatistics`, `realTeamFoodWithPhotos`, `realWeeklyTeamReport`, `weeklyCardSubtitleFromRealData`, `noDemoNamesOnAnalytics`.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `./gradlew :core:allTests :androidApp:testDebugUnitTest :androidApp:lintDebug` → PASS.
- [ ] **Step 5: Commit** `feat(android): staff analytics and weekly report on real data`.

### Task 7: Deploy, release build and check end to end

**Files:**
- Modify: `docs/ANDROID.md` (staff screens on real data, offline copy), `docs/API.md` (already in Task 1)

- [ ] **Step 1:** With the owner's OK: push; production deployment of the pushed commit through the Vercel MCP (`create_deployment`, `target: "production"` — the project's production branch is not this branch); `READY`; `/v1/health` ok; `GET /v1/staff/team` without a token → 401. This deploy also carries plan 1's `verify-email` change (password required), so the owner gets the new release APK in the same step (skill releasing-pulse-builds).
- [ ] **Step 2:** Release APK against the beta server, installed over the existing one on the emulator (same signature, keeps sign-in). With the owner signed in as staff and a player account in the team that has synced at least a sleep night and a mood entry: «Сегодня», «Игроки», the player card, «Аналитика» show that player's real values; airplane mode → the copy stays with «Обновлено …».
- [ ] **Step 3: Commit** docs — `docs: staff screens in the app on real data`.
