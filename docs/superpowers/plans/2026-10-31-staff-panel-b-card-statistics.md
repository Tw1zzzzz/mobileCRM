# Staff panel B — player card, «Статистика», Faceit, «Питание команды» Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Staff open any player of their team in a card with all the player's data, compare state against game on «Статистика», see Faceit matches fetched by the server, and browse the team's food diary (read-only) — on the owner's own team's real data.

**Architecture:** Same as plan A: Server Components in `server/app/staff/(panel)/`, data only through `server/lib/staff/access.ts`, filters in the URL, Server Actions for writes. New pure TypeScript modules (`series.ts`, `statistics.ts`, `game.ts`, archetype) each get a Kotlin twin in `core` and a shared JSON fixture read by both test suites (the plan A pattern: `StaffMetricsParityTest`). Faceit matches are fetched by the server with a key from `FACEIT_API_KEY` and cached in Postgres. Staff notes and images are new Postgres data, deleted when the player leaves the team.

**Tech Stack:** Next.js 16 App Router, TypeScript, postgres.js, zod, vitest, CSS Modules, hand-written SVG; Kotlin Multiplatform `core` (kotlinx.serialization) for the parity twins. No Android UI changes in this plan.

**Spec:** `docs/superpowers/specs/2026-10-28-staff-panel-design.md` (tabs «Игроки» card, «Питание команды», «Статистика», «Хранение файлов», «Ответы владельца», MVP section). Builds on plan A (`docs/superpowers/plans/2026-10-29-staff-panel-a-overview.md`, deployed) and the research notes in `.superpowers/planb-research.md` (git-ignored; file:line facts for every task below — read the section named in the task).

## Global Constraints

- Design source of truth: `docs/superpowers/specs/2026-10-28-staff-panel-design-boards/Players.dc.html` (card), `Statistics.dc.html`, `TeamNutrition.dc.html`. Layout, fonts, spacing, copy, thresholds verbatim; demo names (vexa…, «Atlant Esports», «Coach»), «Демо: открыть как игрок», generated numbers and fixed demo dates are never carried over. Reuse plan A's tokens (`app/staff/staff.module.css`) and helpers (`lib/staff/overview-format.ts`: `NO_DATA`, `fmt1`, `hm`, `plural`).
- Empty rule: a value without data shows «нет данных»; no invented numbers. Pearson `r` needs ≥ 3 paired days and non-zero variance, else the pair shows «мало данных».
- Privacy (unchanged from plan A): staff read only `teamPlayer` members of their team through `access.ts`; `body-profile` never; `lineups` team-visible only; `player-survey` as scores only (ruling A9) — the questionnaire answers, including «Роль в CS2», stay unreadable. Every `page.tsx`, Server Action and `route.ts` under `app/staff/(panel)` starts with `requireStaffContext()` (the guard test is extended in Task 5 to cover `route.ts`).
- Days: calendar days in `STAFF_TIME_ZONE` (default `Europe/Moscow`) for real instants (sleep end, cognitive tests, meals, Faceit matches, ELO snapshots); mood, energy and the balance wheel use the phone day (`phoneDayOf`, UTC date of instant + 12 h); screen-time `date` is a phone day too.
- Names the player sees win over the board's invented ones: cognitive tests «Скорость реакции», «Go / No-Go», «Тест Струпа», «Объём памяти», «Точность прицела»; game tests «До тренировки» / «После тренировки»; wheel labels from the app's `BalanceCategory` (Профессия, Среда).
- Faceit: env `FACEIT_API_KEY` (read lazily; missing → «Faceit не подключён к серверу», never an error page); `GET https://open.faceit.com/data/v4/players/{id}/games/cs2/stats?limit=100` with `Authorization: Bearer`; cache 15 minutes in Postgres, stale cache served when Faceit fails; at most 100 matches (same cap as the app), the UI says from which date the matches are covered when the period reaches further back.
- Board strength labels for correlations: |r| ≥ 0.7 «Сильная», ≥ 0.4 «Заметная», ≥ 0.2 «Слабая», else «Связи почти нет»; suffix « прямая связь» / « обратная связь» when |r| ≥ 0.2. Matrix cell colour: `rgba(0,140,255,a)` positive, `rgba(255,122,61,a)` negative, `a = 0.12 + |r|·0.7`. Team mode pools player-day pairs.
- «Игровая статистика» (пракки, турниры), shared calendar/lineups and food comments are plans C/D: the sources «Пракки» and «Турниры» are shown disabled as «Пракки (скоро)» / «Турниры (скоро)»; the dish dialog has no «Комментарий игроку».
- Rounding in every TS/Kotlin pair: half-up (`floor(x + 0.5)`, plan A's `roundHalfUp`/`round2`), never `kotlin.math.round`.

## Review Focus

- A staff member requests another team's player id in `?player=`, a photo URL, a note, an image or a Faceit lookup → 404/«нет данных», nothing about that player. Tests `cardOfAnotherTeamsPlayerIs404` (Task 4), `photoRouteChecksRoster` (Task 5), `noteActionsRejectForeignPlayer` (Task 8).
- A player with no Faceit link, a missing `playerId`, or the server without `FACEIT_API_KEY`, or Faceit answering 429/500 → the Игра/Корреляции tabs show a clear message, the rest of the card renders. Test `faceitFailuresDegradeTheCard` (Task 6).
- A player has two mood entries per day, a day with only a short sleep, or no data in the period at all → averages use present days only; the card and Статистика never show 0 for missing data. Tests `gapsAreAbsentNotZero` (Task 1), `cardWithoutAnyDataSaysNoData` (Task 4).
- A player leaves the team (or deletes the account) → their notes, images and the staff's access vanish. Tests `removeMemberDeletesNotesAndImages` (Task 8), `removedPlayerCardIs404` (Task 4).
- An uploaded file lies about its type (HTML/SVG/script named `.png`, oversized, truncated) → rejected; stored files are served with the sniffed type and `nosniff`. Test `imageUploadSniffsAndLimits` (Task 8).

---

### Task 1: Per-day series for one player (TS + Kotlin, shared fixture)

**Files:**
- Create: `server/lib/staff/series.ts`, `server/test/staff-series.test.ts`, `server/test/fixtures/staff-series.json`, `core/src/commonMain/kotlin/com/pulse/core/feature/StaffSeries.kt`, `core/src/jvmTest/kotlin/com/pulse/core/feature/StaffSeriesParityTest.kt`
- Modify: `core/build.gradle.kts` (declare the new fixture as a `jvmTest` input, as plan A did for `staff-metrics.json`)

**Interfaces:**
- Consumes: plan A `Documents`/`PlayerDocuments`, `dayOf`, `phoneDayOf`, `shiftDay`, `dailyInputs`, `readiness` (`lib/staff/metrics.ts`); Kotlin twins in `StaffMetrics.kt`. Research §D (shapes), §B1.
- Produces (Kotlin: same names on `object StaffSeries`):
  ```ts
  type DaySeries = Record<Day, number>;            // absent key = no data
  type PlayerSeries = { sleep: DaySeries; mood: DaySeries; energy: DaySeries; cog: DaySeries; kcal: DaySeries; screen: DaySeries; balance: DaySeries; focus: DaySeries; fatigue: DaySeries; elo: DaySeries; readiness: DaySeries };
  playerSeries(docs: PlayerDocuments, from: Day, to: Day, tz: string): PlayerSeries   // inclusive, only days with data
  periodDays(to: Day, days: number): { from: Day; to: Day; days: Day[] }
  ```
  Units: `sleep` hours (sessions ≥ 60 min ending that day, summed); `mood`/`energy` day mean; `cog` day mean of `cognitive.results[].score`; `kcal` day sum of meals; `screen` hours (`minutes/60`); `balance` mean of the 8 wheel values per day, a missing key counting 5.0 as the app does (last entry of the day wins); `focus`/`fatigue` day mean of `cognitive.gameResults[]`; `elo` last `faceit.snapshots[]` value of the day; `readiness` = plan A `readiness(dailyInputs(...))` for each day that has any input.

- [ ] **Step 1: Write the fixture and failing tests** (`staff-series.test.ts` + parity over `staff-series.json`, ≥ 12 cases): `sleepNightEndsOnTheDay`, `shortSleepIgnored`, `moodUsesPhoneDay` (Yekaterinburg `2026-10-29T19:00:00Z` → 30 Oct), `screenHoursFromMinutes`, `balanceMeanOfEightMissingKeyIsFive`, `eloLastSnapshotOfTheDay`, `focusFatigueDayMean`, `readinessSeriesMatchesDailyInputs`, `gapsAreAbsentNotZero`, `periodDaysEndsToday` (7 days = to−6..to).
- [ ] **Step 2:** `cd server && npm test -- staff-series` and `./gradlew :core:jvmTest --tests '*StaffSeriesParity*'` → FAIL.
- [ ] **Step 3: Implement both** (pure, no stores; the Kotlin object reuses `StaffMetrics` helpers).
- [ ] **Step 4:** both commands → PASS; `npm test`, `npx tsc --noEmit`, `./gradlew :core:allTests` → PASS.
- [ ] **Step 5: Commit** `feat: per-day staff series for one player, TS and Kotlin`.

### Task 2: Faceit on the server (client, mapper, cache, summary)

**Files:**
- Create: `server/db/migrations/007_faceit_cache.sql`, `server/lib/faceit/{client.ts,mapper.ts,cache.ts}`, `server/lib/staff/game.ts`, `server/test/faceit.test.ts`, `server/test/fixtures/faceit-matches.json`, `core/src/jvmTest/kotlin/com/pulse/core/feature/FaceitParityTest.kt`
- Modify: `core/build.gradle.kts` (fixture input), `docs/STAFF_PANEL.md` (env var, manual step — in Task 11)

**Interfaces:**
- Consumes: research §E. Core `FaceitMapper.match` (`core/.../model/Faceit.kt:160`) and `GameSummary` (`model/Game.kt:48`) are the behaviour to mirror; the sample payload and expected values are in `FaceitValidationTests.kt:55-76,78-86`.
- Produces:
  ```ts
  type FaceitMatch = { id: string; map: string; won: boolean; scoreOwn: number; scoreOther: number; kills: number; deaths: number; assists: number; adr: number; rounds: number; finishedAt: string /* ISO */; firstKills?: number; entryCount?: number; entryWins?: number; clutchWins?: number; multiKills?: { k2: number; k3: number; k4: number; k5: number }; utilityDamage?: number; enemiesFlashed?: number };
  mapMatch(item: unknown): FaceitMatch | null                  // port of FaceitMapper.match incl. loose number parsing, "1,25", seconds vs ms
  type FaceitResult = { status: "ok"; matches: FaceitMatch[]; fetchedAt: Date; stale: boolean } | { status: "no-key" | "no-player" | "not-found" | "rate-limited" | "unavailable" };
  loadMatches(deps: Deps, userId: string, faceit: { playerId?: string; nickname?: string }, http?: typeof fetch): Promise<FaceitResult>
  gameSummary(matches: FaceitMatch[], from: Day, to: Day, tz: string): GameTiles   // every tile of the board's «Игра» tab; tiles Faceit cannot give (KAST, First deaths, Ср. время ослепления, Ничьи, Пистолетные…, Pistol Win-Rate) are null
  matchDaily(matches: FaceitMatch[], from: Day, to: Day, tz: string): { kd: DaySeries; adr: DaySeries; winRate: DaySeries }   // day mean of per-match K/D (kills/max(1,deaths)), ADR, 100/0 win
  ```
- Table `faceit_cache(user_id uuid primary key references users on delete cascade, matches jsonb not null, fetched_at timestamptz not null)`; TTL constant 15 minutes; when `playerId` is missing but `nickname` exists, resolve via `GET /players?nickname=&game=cs2`; neither → `no-player`. A global `rateLimit(deps, "faceit:fetch", 60, 60_000)` protects the key; exceeded → serve stale or `rate-limited`. Request timeout 5 s.

- [ ] **Step 1: Write failing tests:** `mapMatchMatchesCoreSample` (the `FaceitValidationTests` payload), `lossTakesLowerScore`, `skipsItemsWithoutKillsOrDeaths`, `missingKeyIsNoKey`, `cacheServedWithinTtl` (second call makes no HTTP request), `staleCacheWhenFaceitFails`, `status429IsRateLimited`, `nicknameFallbackResolvesPlayer`, `gameSummaryTilesAndNulls`, `matchDailyMeans`; fixture-driven `FaceitParityTest.kt`: Kotlin `FaceitMapper.match` + the app's summary code on the fixture's raw items give the same mapped fields and tile values as the TS expectations.
- [ ] **Step 2:** `npm test -- faceit` and `./gradlew :core:jvmTest --tests '*FaceitParity*'` → FAIL.
- [ ] **Step 3: Implement.** HTTP via injected `fetch` so tests never touch the network.
- [ ] **Step 4:** both → PASS; `npm test`, `npx tsc --noEmit`, `./gradlew :core:allTests`.
- [ ] **Step 5: Commit** `feat(server): Faceit matches for staff with a cache`.

### Task 3: Correlation math (TS + Kotlin, shared fixture)

**Files:**
- Create: `server/lib/staff/statistics.ts`, `server/test/staff-statistics.test.ts`, `server/test/fixtures/staff-statistics.json`, `core/src/commonMain/kotlin/com/pulse/core/feature/StaffStatistics.kt`, `core/src/jvmTest/kotlin/com/pulse/core/feature/StaffStatisticsParityTest.kt`
- Modify: `core/build.gradle.kts` (fixture input)

**Interfaces:**
- Consumes: `DaySeries` (Task 1), `matchDaily` (Task 2). Research §B2, §B4. Core precedent: `StatisticsMath.pearson` (null for n < 3 or zero variance), edge cases in `CoreUtilTests.kt:152-154` and `StatisticsTests.kt:73-79`.
- Produces (pure; Kotlin mirrors names):
  ```ts
  pairByDay(a: DaySeries, b: DaySeries): { xs: number[]; ys: number[] }      // days present in both, ascending
  pearson(xs: number[], ys: number[]): number | null
  strength(r: number | null): { label: string; direction: "direct" | "inverse" | null }   // Global Constraints labels
  correlate(a: DaySeries, b: DaySeries): number | null
  correlatePooled(pairs: { a: DaySeries; b: DaySeries }[]): number | null     // all players' paired days in one sample
  medianSplit(xs: number[], ys: number[]): { median: number; hiMean: number; loMean: number } | null   // board: median = sorted[floor(n/2)], hi = A ≥ median
  keyLinks<T>(matrix: ..., cross: Set<string>): ...   // top 3 by |r|; cross (state×game) pairs first, else all; game-first pairs swapped state-first
  STAT_METRICS: readonly { id: "mood"|"energy"|"sleep"|"screen"|"cog"|"balance"|"kcal"|"elo"|"kd"|"adr"|"wr"; label: string; lower: string; group: "state"|"game"; unit: string }[]   // labels verbatim from the board's MET table; TS only
  ```
  The board's `kast` metric is dropped (Faceit cannot give it).

- [ ] **Step 1: Write the fixture and failing tests** (≥ 12 cases): `pearsonPerfect`, `pearsonTooFewPairsIsNull`, `pearsonZeroVarianceIsNull`, `pairByDayDropsGaps`, `strengthLabelsEdges` (0.7/0.4/0.2 and negatives), `pooledUsesAllPlayers`, `medianSplitMatchesBoard`, `keyLinksPrefersCrossPairs`, `keyLinksSwapsGameFirst`; Kotlin parity over the fixture.
- [ ] **Step 2:** `npm test -- staff-statistics` and `./gradlew :core:jvmTest --tests '*StaffStatisticsParity*'` → FAIL.
- [ ] **Step 3: Implement both.**
- [ ] **Step 4:** both → PASS; full `npm test`, `npx tsc --noEmit`, `./gradlew :core:allTests`.
- [ ] **Step 5: Commit** `feat: staff correlation math, TS and Kotlin, one fixture`.

### Task 4: Player card — frame, KPI strip, «Тесты», «Сон»

**Files:**
- Create: `server/lib/staff/card.ts` (`loadCard`, `parseCardParams`, `cardHref`), `server/components/staff/card/{CardFrame,KpiStrip,TestsTab,SleepTab}.tsx`, `server/components/staff/card/card.module.css`, `server/test/staff-card.test.tsx`, `server/test/staff-card-load.test.ts`
- Modify: `server/app/staff/(panel)/players/page.tsx` (card section under the roster), `server/components/staff/players/Roster.tsx` (cards become links to `?player=<id>`; selected card marked; board's period chips `7 / 30 / 90 дней` in the header), `server/lib/staff/access.ts` (export `rosterPlayer(deps, ctx, id)`; `TeamPlayer` gains `faceitUrl: string | null` from `users.faceit_url`)

**Interfaces:**
- Consumes: Tasks 1; plan A `loadPlayers`. Research §A1 (items 1–4, 6, 11), §A2.
- Produces: URL `/staff/players?player=<id>&period=7|30|90&tab=profile|tests|game|corr|wheel|food|sleep&type=all|cog|game&src=all|faceit&day=<Day>`; invalid or foreign `player` → the first roster player (no error, nothing leaked); empty team → no card. `loadCard(deps, ctx, params): Promise<CardData>` loads all synced collections the card needs through one `playerDocuments` call. Selected default: first player, tab `profile`, period 30.
- KPI strip per board: «Средний score» (mean of `cog` day values, delta = second-half mean − first-half mean, `up` when ≥), «Индекс состояния» (daily `readiness`, same delta rule), «Фокус high» = `{n} из {days with data}` caption «дней с фокусом 8+», «Усталость high» likewise «…с усталостью 8+».
- «Тесты»: chart (viewBox 900×220, Score cyan solid / «Индекс состояния» orange dashed, y = 180 − v·1.6) and table «История тестов» (`Дата | Тест | Score | Индекс состояния`); select «Тип теста» = `Все тесты | Когнитивные | Игровые до/после` (no «Еженедельные»). «Сон»: sub «Средняя продолжительность {H ч MM мин} · ночей короче 7 ч: {n} из {N}», table «Дневник сна» (`Дата сна | Начало | Окончание | Длительность`, last 10 nights, `st-bad` under 7 h).

- [ ] **Step 1: Write failing tests:** `cardOfAnotherTeamsPlayerIs404` (foreign/solo/staff id → falls back to the first roster player and the foreign nickname is absent from the markup), `removedPlayerCardIs404`, `kpiStripFromRealSeries`, `testsTableRealNames`, `sleepTabAveragesAndShortNights`, `cardWithoutAnyDataSaysNoData` (every block «нет данных», no `NaN`/`0`), `rosterCardsAreLinks`, `noDemoStringsInCard`, `periodChipsKeepPlayer`.
- [ ] **Step 2:** `npm test -- staff-card` → FAIL.
- [ ] **Step 3: Implement** markup/CSS ported from `Players.dc.html` lines 143–223 and 280–289; tabs not yet built (`game`, `corr`, `wheel`, `food`, `profile`) render a «Скоро» panel until their tasks land.
- [ ] **Step 4:** `npm test`, `npx tsc --noEmit`, `npx next build` → PASS.
- [ ] **Step 5: Commit** `feat(server): staff player card with tests and sleep`.

### Task 5: Player card — «Колесо баланса», «Питание», staff photo route

**Files:**
- Create: `server/components/staff/card/{WheelTab,FoodTab}.tsx`, `server/app/staff/(panel)/photos/[playerId]/[mealId]/route.ts`, `server/lib/staff/photos.ts`, `server/test/staff-card-food.test.tsx`, `server/test/staff-photos.test.ts`
- Modify: `server/test/staff-guard.test.ts` (also scan every `route.ts` under `app/staff/(panel)`), `server/lib/staff/card.ts`, `docs/STAFF_PANEL.md` (panel now shows meal photos)

**Interfaces:**
- Consumes: Task 4 frame; plan 2 `meal_photos(user_id, meal_id, jpeg)`; `rosterPlayer`. Research §A1 (9, 10), §C last bullets.
- Produces: `GET /staff/photos/<playerId>/<mealId>` → `image/jpeg`, `cache-control: private, max-age=3600`, `x-content-type-options: nosniff`; 404 when the player is not on the caller's roster, the id is not a GUID, or there is no photo; `staffMealPhoto(deps, ctx, playerId, mealId): Promise<Uint8Array | null>`.
- «Колесо баланса»: 7 day chips (label «Сегодня» / «{вс пн вт ср чт пт сб} {d}» + « · —» without an entry), radar 340×340 (centre 170, radius 115, spokes from −π/2 every π/4, rings 2.5/5/7.5/10), list of 8 `full name — value`, empty «Игрок не заполнил колесо баланса в этот день». «Питание»: sub «Записей: {n} · в среднем {X} ккал в день» (mean of daily sums over days with meals), table «Дневник питания» (`Фото | Дата | Блюдо | Калорийность`) with a real thumbnail via the photo route when `photoRef` is true, else the placeholder.

- [ ] **Step 1: Write failing tests:** `wheelDayWithoutEntryShowsEmpty`, `wheelUsesAppLabels`, `foodAveragesOverDaysWithMeals`, `foodThumbUsesPhotoRouteOnlyWithPhotoRef`, `photoRouteChecksRoster` (other team's player → 404, no bytes), `photoRouteRejectsBadIds`, `guardCoversRouteHandlers` (a `route.ts` without `requireStaffContext()` fails the guard test).
- [ ] **Step 2:** `npm test -- staff-card-food staff-photos staff-guard` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `npm test`, `npx tsc --noEmit`, `npx next build` → PASS.
- [ ] **Step 5: Commit** `feat(server): staff card wheel, food diary and meal photos`.

### Task 6: Player card — «Игра» and «Корреляции» (Faceit)

**Files:**
- Create: `server/components/staff/card/{GameTab,CorrTab,FaceitNotice}.tsx`, `server/test/staff-card-game.test.tsx`
- Modify: `server/lib/staff/card.ts` (fetch Faceit only when `tab` is `game` or `corr` and `src` is `all` or `faceit`)

**Interfaces:**
- Consumes: Tasks 1–4. Research §A1 (7, 8), §E.
- Produces: «Игра» tab with the three groups `K/D` (`Убийства | Смерти | Ассисты | K/D`), `Аналитические метрики` (13 tiles) and `Матчи, раунды, пистолетки` (11 tiles) from `gameSummary`; null tiles show «нет данных». Filter «Источник игр» = `Все | Faceit | Пракки (скоро) | Турниры (скоро)` (the last two disabled). «Корреляции»: 5 state rows (`Сон | Настроение | Энергия | Экранное время | Когнитивный индекс`) × 3 game columns (`K/D | ADR | Win Rate`), cells `+0.00`/`−0.00` (U+2212) or «мало данных», the «Главное: …» footer with the board's sentences. `FaceitNotice` maps non-ok statuses to messages: «Faceit не подключён к серверу», «У игрока не подключён Faceit», «Игрок на Faceit не найден», «Faceit временно недоступен — попробуйте позже»; stale data adds «Данные от {дата, время}».

- [ ] **Step 1: Write failing tests:** `gameTilesFromMatches`, `unavailableTilesSayNoData`, `disabledSourcesAreNotLinks`, `corrCellsAndColours`, `corrFooterNamesStrongestPair`, `faceitFailuresDegradeTheCard` (each non-ok status renders its message and the other tabs still load), `periodBeyondCoverageSaysFromWhichDate`, `noFaceitCallOnOtherTabs` (a spy `fetch` is never called for `tab=sleep`).
- [ ] **Step 2:** `npm test -- staff-card-game` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `npm test`, `npx tsc --noEmit`, `npx next build` → PASS.
- [ ] **Step 5: Commit** `feat(server): staff card game and correlations from Faceit`.

### Task 7: Player card — «Профиль»: identity, links, archetype (TS + Kotlin)

**Files:**
- Create: `server/lib/staff/archetype.ts`, `server/components/staff/card/{IdentityRow,ProfileTab}.tsx`, `server/test/staff-archetype.test.ts`, `server/test/fixtures/staff-archetype.json`, `core/src/jvmTest/kotlin/com/pulse/core/feature/StaffArchetypeParityTest.kt`
- Modify: `server/lib/staff/mindset.ts` (trait titles, short titles, leading-trait summaries ported from `SurveyTexts.kt:157-254`), `core/build.gradle.kts` (fixture input)

**Interfaces:**
- Consumes: Task 4 frame; `mindset[]` scores from `access.ts`; core `MindsetArchetype.forTrait` (`model/Survey.kt:65-108`, `SurveyTexts.kt`). Research §A1 (1, 5).
- Produces: `archetypeOf(scores: Record<Trait, number> | null): { title: string; summary: string; tags: string[] }` — leading trait of the latest «Игровой характер» run, «Ровный по всем чертам» when max − min < 12; tags = short titles of strengths (≥ 70, up to 3). Identity row: initials avatar, nickname, «Последняя активность: …» (plan A `lastActivityLabel`), buttons «Telegram» and «Steam» (disabled, title «нет данных»; the app has no such fields) and «FACEIT» (a link only when `faceitUrl` starts with `https://www.faceit.com/`, else disabled). «Профиль» tab: card «Архетип» (title, summary, tags; «нет данных» without a finished run). The «Роль в CS2» card is not built (questionnaire stays private; see the owner question in the plan review).

- [ ] **Step 1: Write failing tests:** `archetypeLeadingTrait`, `archetypeBalancedWhenSpreadUnder12`, `archetypeNullWithoutRuns`, `faceitLinkOnlyForFaceitHost` (a `javascript:` or foreign-host value renders no link), `identityRowHasNoDemoNames`; Kotlin parity over `staff-archetype.json` against `MindsetArchetype`.
- [ ] **Step 2:** `npm test -- staff-archetype` and `./gradlew :core:jvmTest --tests '*StaffArchetypeParity*'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** both → PASS; `npm test`, `npx tsc --noEmit`, `./gradlew :core:allTests`, `npx next build`.
- [ ] **Step 5: Commit** `feat(server): staff card profile with archetype`.

### Task 8: Player card — staff notes and images

**Files:**
- Create: `server/db/migrations/008_staff_player_notes.sql`, `server/lib/staff/notes.ts`, `server/app/staff/(panel)/files/[playerId]/[kind]/route.ts`, `server/components/staff/card/{NotesForm,ImageSlot}.tsx`, `server/test/staff-notes.test.ts`
- Modify: `server/app/staff/actions.ts` (`saveNoteAction`, `uploadImageAction`, `deleteImageAction`), `server/lib/team/members.ts` (`removeMember` deletes the player's notes and images in its transaction), `server/next.config.ts` (`experimental.serverActions.bodySizeLimit: "3mb"`), `server/components/staff/card/ProfileTab.tsx`

**Interfaces:**
- Consumes: Tasks 5 (route guard test), 7; `rosterPlayer`; plan A actions pattern (`removeMemberCore`: core logic takes `ctx`, never form team ids). Research §A1 (5), §G.
- Produces: tables `staff_player_notes(player_id uuid primary key references users on delete cascade, team_id uuid not null references teams on delete cascade, communication text not null, updated_at timestamptz not null, updated_by uuid)` and `staff_player_images(player_id uuid references users on delete cascade, kind text check (kind in ('scheme','roadmap','mindmap')), team_id uuid not null references teams on delete cascade, content_type text not null, bytes bytea not null, updated_at timestamptz not null, updated_by uuid, primary key (player_id, kind))`; `saveNote(deps, ctx, playerId, text): Promise<void>` (≤ 5000 chars, last write wins, shows «Изменено {дата} · {nickname}»); `saveImage(deps, ctx, playerId, kind, bytes: Uint8Array): Promise<void>` — PNG/JPEG/WebP by magic bytes only, ≤ 2 000 000 bytes, content type from sniffing; `GET /staff/files/<playerId>/<kind>` serves with the sniffed type, `nosniff`, `cache-control: private, no-store`, roster-checked. UI copy from the board: group «Коммуникативная линия», sub «Круг общения в команде, статус общения, открытые вопросы — заметки видит только staff», drops «Схема общения» / «Roadmap» / «Mindmap» with «Загрузите изображение схемы» / «План развития игрока» / «Карта мышления» and button «Загрузить»; after an upload the image with «Заменить» and «Удалить».

- [ ] **Step 1: Write failing tests:** `noteSavedAndVisibleToOtherStaff`, `noteActionsRejectForeignPlayer`, `noteTooLongRejected`, `imageUploadSniffsAndLimits` (HTML named `.png`, SVG, 2 000 001 bytes, empty → rejected; a real PNG/JPEG/WebP stored), `imageRouteChecksRoster`, `playersCannotReadNotes` (no player-facing route exposes them), `removeMemberDeletesNotesAndImages`, `accountDeletionDeletesNotes`, `actionsRequireStaffContext` (guard test passes).
- [ ] **Step 2:** `npm test -- staff-notes staff-guard` → FAIL.
- [ ] **Step 3: Implement.** Check that Vercel accepts a 3 MB body for a Server Action; if the platform cap is lower, say so in the report and lower both limits.
- [ ] **Step 4:** `npm test`, `npx tsc --noEmit`, `npx next build` → PASS.
- [ ] **Step 5: Commit** `feat(server): staff notes and images on the player card`.

### Task 9: «Статистика»

**Files:**
- Create: `server/app/staff/(panel)/statistics/page.tsx`, `server/lib/staff/statistics-page.ts` (`loadStatistics`, `parseStatsParams`, `statsHref`), `server/components/staff/statistics/{Picker,LinkCard,Matrix,SmallMultiples,GameCard,Scope}.tsx`, `server/components/staff/statistics/statistics.module.css`, `server/test/staff-statistics-page.test.tsx`, `server/test/staff-statistics-load.test.ts`
- Modify: `server/lib/staff/nav.ts` («Статистика» → `/staff/statistics`)

**Interfaces:**
- Consumes: Tasks 1–3. Research §B1–B3.
- Produces: URL `/staff/statistics?who=team|<playerId>&m=<id,id,…>&period=7|30|90|365|custom&from=&to=`; invalid ids/metrics fall back (unknown `who` → `team`, unknown metric dropped, > 8 metrics cut to 8, bad custom range → last 30 days); default metrics `mood,sleep,screen,elo,kd,adr`. Page (correlation mode of the board only): scope chips «Вся команда» + players, metric picker («Выберите метрики для сравнения», «Слева состояние, справа игра. Выбрано {n} из 8», «Можно выбрать до 8 метрик — снимите одну, чтобы добавить другую», «Сбросить выбор»), «Что из состояния влияет на игру» key-link cards (`{A} ↔ {B}`, `r = ±0.00`, bar, strength label, the board's example sentence from `medianSplit`), collapsible «Таблица корреляций» (`<details>`, no JS), «Динамика выбранных метрик» small multiples (SVG 300×90), «Игровые показатели за период» (`Сыграно карт | Средний K/D | Средний ADR | Win Rate | ELO на конец периода` from Faceit; Faceit status messages as in Task 6). Team mode: state series per player, Faceit fetched per player (parallel, max 12 players), pooled correlations. Removed from the board: «в подписке» block, «Тарифы», next-event/streak/bell chrome, «Обзор показателей» mode, «AI-вывод», the `kast` metric. Empty state for staff: «Пока нет данных за этот период» with «Игроки ещё не заполнили состояние или не подключили Faceit — графики появятся, когда данные синхронизируются.».

- [ ] **Step 1: Write failing tests:** `statsParamsFallBacks`, `defaultMetricsAndMaxEight`, `keyLinksFromSyncedAndFaceitData`, `teamModePoolsPlayers`, `playerModeIgnoresOthers` (another team's player id → team), `tooFewDaysSaysNotEnoughData`, `faceitDownStillShowsStateMetrics`, `noSubscriptionOrDemoStrings`, `pageStartsWithRequireStaffContext` (guard test), `sidebarLinksStatistics`.
- [ ] **Step 2:** `npm test -- staff-statistics-page staff-statistics-load` → FAIL.
- [ ] **Step 3: Implement** markup/CSS from `Statistics.dc.html` (cards, picker, matrix, multiples).
- [ ] **Step 4:** `npm test`, `npx tsc --noEmit`, `npx next build` → PASS.
- [ ] **Step 5: Commit** `feat(server): staff Statistics on real data`.

### Task 10: «Питание команды» (read-only)

**Files:**
- Create: `server/lib/staff/nutrition.ts` (`loadNutrition`, `parseNutritionParams`, `nutritionHref`), `server/components/staff/players/{NutritionFeed,DaySummary,DishDialog}.tsx`, `server/test/staff-nutrition.test.tsx`, `server/test/staff-nutrition-load.test.ts`
- Modify: `server/app/staff/(panel)/players/page.tsx` (`?section=nutrition`), `server/components/staff/players/Roster.tsx` (the «Питание команды» segment becomes a link)

**Interfaces:**
- Consumes: Tasks 1, 5 (photo route). Research §C.
- Produces: URL `/staff/players?section=nutrition&who=all|<id>&days=1|3|7&photos=1&meal=<GUID>`. Filter bar: «Игрок» chips (`Все игроки` + nicknames), «Период» chips `Сегодня / 3 дня / 7 дней` (default 3), chip «Только с фото». Feed grouped by day (newest first; label «Сегодня, 31 октября, пятница» / «Вчера, …» / «{d} {месяца}, {день недели}»), cards with photo or «без фото», nickname badge, `{ккал} ккал · {HH:MM}`; empty «Записей нет. Попробуйте выключить «Только с фото» или выбрать другой период». Aside «Сводка за день»: per player `{kcal} / {goal} ккал`, `{n} приём/приёма/приёмов пищи`, bar, `Б Ж У` grams, `Вода {x,x} л`, status «Нет записей» / «Мало записей» (1 meal) / «В норме» (≥ 2); goal = the player's `nutrition.goals` (default 2 400 kcal, 2 500 ml water); footer «Норма — из настроек игрока (по умолчанию 2 400 ккал)»; water read from `nutrition.water["Y-M-D"]` (unpadded phone-local key). Dish dialog (server-rendered via `?meal=`, close = link without it): photo, nickname, source (`AI-анализ фото` / `Вручную` / `Быстрое добавление`), name, time, tiles `Ккал | Белки | Жиры | Углев.`; no comment field. Player filter links keep the other params.

- [ ] **Step 1: Write failing tests:** `feedGroupsByStaffDayNewestFirst`, `onlyWithPhotoFilter`, `summaryUsesPlayerGoals`, `summaryStatusByMealCount`, `waterKeyIsUnpaddedPhoneDay`, `dishDialogForForeignMealIdIsClosed` (a GUID not in the roster's meals renders no dialog), `noCommentFieldInDialog`, `emptyFeedMessage`, `noDemoMealsInvented`, `sectionKeepsPlayerFilterLinks`.
- [ ] **Step 2:** `npm test -- staff-nutrition` → FAIL.
- [ ] **Step 3: Implement** from `TeamNutrition.dc.html` (lines 117–191, 199–217, 281–318).
- [ ] **Step 4:** `npm test`, `npx tsc --noEmit`, `npx next build` → PASS.
- [ ] **Step 5: Commit** `feat(server): staff team nutrition feed`.

### Task 11: Docs, deploy and end-to-end check

**Files:**
- Modify: `docs/STAFF_PANEL.md` (card, Статистика, Питание команды, Faceit key and cache, notes/images and their deletion, what stays «Скоро»), `docs/API.md` only if a wire detail changed (none expected)

- [ ] **Step 1:** Docs as above, in Russian like the rest of the file; record the manual owner step: create a Faceit Data API key (server-side) at developers.faceit.com and add it as `FACEIT_API_KEY` to the Vercel project `pulse` (Production), never in chat.
- [ ] **Step 2:** `cd server && npm test && npx tsc --noEmit && npx next build`; `./gradlew :core:allTests` → PASS. Commit `docs: staff panel B`.
- [ ] **Step 3:** With the owner's OK: push the branch, create a production deployment of the pushed commit through the Vercel MCP (`target: "production"`), check `READY`, `/v1/health`, `/staff/login`. No new APK is needed (no app change); migrations 007–008 run in the build.
- [ ] **Step 4:** With the owner: sign in at `/staff`, open a player with synced data and walk the tabs, switch «Статистика» between team and player, open «Питание команды» and a photo, save a note and upload an image, rotate nothing. Anything that disagrees with the app is a bug, not a note.
