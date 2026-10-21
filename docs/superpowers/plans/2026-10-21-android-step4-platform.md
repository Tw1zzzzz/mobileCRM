# Android step 4 — platform integrations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect the Android app to the phone: Health Connect (activity, sleep, heart), real screen time (usage statistics), local notifications (events, game tests, bedtime, morning check, weekly report), sleep tracking in an ongoing notification, a home-screen widget and a Quick Settings tile.

**Architecture:** Rules and data assembly live in the shared `core` (pure Kotlin, unit-tested, same results as iOS). The Android app only reads platform APIs into plain core types, schedules work and posts notifications. `PulseCore` already accepts a `HealthDataProvider`; screen time gets an analogous `applyUsage`.

**Tech Stack:** Kotlin 2.4, Compose, `androidx.health.connect:connect-client:1.1.0`, `androidx.work:work-runtime-ktx:2.12.0`, `androidx.glance:glance-appwidget:1.2.0`, UsageStatsManager, NotificationManagerCompat, TileService.

**Spec:** iOS behaviour is the spec — `Pulse/App/SystemIntegrations.swift`, `Pulse/App/SleepTracking.swift`, `Pulse/Stores/HealthKitService.swift`, `PulseWidgets/SleepLiveActivityWidget.swift`; step list in `docs/ANDROID.md` (row 4).

## Global Constraints

- minSdk 28, targetSdk 36, compileSdk 37; all copy in Russian, same wording as iOS.
- Health data is read-only (no write permissions in release); it never leaves the device and never goes to logs.
- No exact alarms (`SCHEDULE_EXACT_ALARM` is not granted by default on Android 14+): reminders use WorkManager with a delay; a few minutes of drift is acceptable.
- No foreground service: sleep tracking is an ongoing notification with a chronometer (iOS Live Activity twin).
- `POST_NOTIFICATIONS` is requested only from an explicit user action (profile toggle, demo button, calendar save), never at launch.
- Every permission denial leaves the app fully usable with manual entry, as today.
- Core rules mirror iOS constants: event reminder 60 min before; game tests 15 min before / 5 min after events whose title contains «тренировк» (case-insensitive), horizon 7 days; bedtime reminder 15 min before bedtime; wake check 30 min after the next planned wake-up; weekly report Sunday 19:00.

## Review Focus

- Health Connect not installed / needs update (Android 9–13) → the card offers «Установить Health Connect», nothing crashes. Test: `HealthConnectAvailabilityTest` (Task 5).
- Permissions granted only partially (e.g. steps yes, sleep no) → snapshot builds with what is readable; missing parts stay empty. Test: `snapshotWithoutSleepOrHeart` (Task 2).
- Event edited or deleted after its reminder was scheduled → old reminder is replaced/cancelled. Guarded by stable ids (`event-<UUID>`, Task 1 `eventReminderIdIsStable`) + unique WorkManager names with `REPLACE` (Task 4), checked on the emulator in Task 4 Step 3 (edit time → one notification at the new time).
- Sleep started at 01:30 after midnight with wake 07:00 → wake check same morning 07:30, not next day. Test: `wakeCheckAfterMidnightStart` (Task 1).
- Usage events spanning midnight / screen-on without screen-off at query end → minutes are clipped to the day and to `now`. Test: `sessionsClippedToDayAndNow` (Task 3).

---

### Task 1: Notification rules in core

**Files:**
- Create: `core/src/commonMain/kotlin/com/pulse/core/feature/NotificationRules.kt`
- Test: `core/src/commonTest/kotlin/com/pulse/core/feature/NotificationRulesTest.kt`

**Interfaces:**
- Produces:
  - `data class ScheduledReminder(val id: String, val at: Instant, val title: String, val body: String, val kind: ReminderKind, val eventId: Uuid? = null, val phase: GameTestPhase? = null)`
  - `enum class ReminderKind { EVENT, GAME_TEST, BEDTIME, WAKE_CHECK, WEEKLY }`
  - `object NotificationRules` with `eventReminder(event: CalendarEvent, now: Instant): ScheduledReminder?`, `gameTests(events: List<CalendarEvent>, now: Instant): List<ScheduledReminder>`, `nextBedtime(window: SleepWindow, now: Instant): ScheduledReminder`, `wakeCheck(start: Instant, window: SleepWindow): ScheduledReminder`, `nextWeekly(now: Instant): ScheduledReminder`, `isTraining(event): Boolean`; ids: `event-<UUID>`, `game-beforeTraining-<UUID>` / `game-afterTraining-<UUID>`, `sleep-bedtime`, `sleep-wake-check`, `weekly-report` (UUID uppercase, as iOS).
  - Copy (verbatim from iOS): event body «Через час · HH:mm–HH:mm»; game test before «Игровой тест перед тренировкой» / ««{title}» скоро начнётся. Оцени фокус и готовность — 20 секунд.»; after «Игровой тест после тренировки» / «Как прошла «{title}»? Отметь фокус и усталость.»; bedtime «Скоро отбой 🌙» / «План — лечь в {bedtime}. Нажми «Начать сон», когда ляжешь: таймер появится в шторке.»; wake «Доброе утро ☀️» / «Сон всё ещё отслеживается с {HH:mm}. Нажми «Я проснулся», чтобы сохранить ночь.»; weekly «Итоги недели готовы» / «Сон, нагрузка, настроение и игра — сравнение с прошлой неделей и план на следующую.»

- [ ] **Step 1: Write failing tests** — `eventReminderOneHourBefore` (event 18:00 → at 17:00, body «Через час · 18:00–19:00»), `eventReminderIdIsStable` (same event edited → same id `event-<UUID>`), `eventReminderSkippedWhenPast` (now 17:30 → null), `gameTestsOnlyForTrainingsWithin7Days` (titles «Тренировка», «Матч»; one 8 days ahead → 2 reminders: 15 min before, 5 min after), `bedtimeWrapsMidnight` (bedtime 00:10 → reminder 23:55 previous evening; if now past it → next day), `wakeCheckAfterMidnightStart` (start 01:30, wake 07:00 → 07:30 same day; start 23:00 → next day 07:30), `weeklyIsNextSunday19` (Wed → this Sun 19:00; Sun 20:00 → next Sun).
- [ ] **Step 2:** `./gradlew :core:jvmTest --tests "*NotificationRulesTest"` → FAIL (unresolved).
- [ ] **Step 3:** Implement `NotificationRules` with `PulseCalendar` helpers (`setting`, `addingDays`, `startOfWeek`).
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `feat(core): notification rules shared with iOS`.

### Task 2: Health snapshot builder in core

**Files:**
- Create: `core/src/commonMain/kotlin/com/pulse/core/feature/HealthSnapshotBuilder.kt`
- Test: `core/src/commonTest/kotlin/com/pulse/core/feature/HealthSnapshotBuilderTest.kt`

**Interfaces:**
- Produces:
  - `data class TimedValue(val start: Instant, val end: Instant, val value: Double)`
  - `data class RawExercise(val id: String, val start: Instant, val end: Instant, val kind: WorkoutKind, val kcal: Double?, val averageHeartRate: Double?)`
  - `data class HealthRawData(val steps: List<TimedValue>, val distanceMeters: List<TimedValue>, val activeKcal: List<TimedValue>, val exercises: List<RawExercise>, val heartRate: List<HeartRateSample>, val restingHeartRate: List<HeartRateSample>, val hrv: List<HeartRateSample>, val respiratoryRate: List<HeartRateSample>, val spo2Percent: List<HeartRateSample>, val weightKg: Double?, val bodyFatPercent: Double?, val leanMassKg: Double?, val sleep: List<SleepInterval>)` (all lists default empty)
  - `object HealthSnapshotBuilder { fun build(raw: HealthRawData, now: Instant, sourceName: String): HealthSnapshot }` — same rules as `HealthKitService.snapshot`: 7-day window for activity, today's summary, active minutes = max(exercise minutes, brisk-walk minutes from per-minute steps), kcal fallback `FitnessMath.estimatedActiveKcal`, zones from today's HR with age 25 (Health Connect has no birth date), night vitals for the last night from `SleepMath.nights`, daily averages of resting HR / HRV for 30 days, workouts `detail = sourceName`, `loadWorkouts` = last 28 days.
- [ ] **Step 1: Write failing tests** — `todaySummaryFromRecords` (steps 3000+2000 today → 5000; distance 3200 m → 3.2 km; kcal 0 → estimated), `activeMinutesUseBriskWalking` (60 one-minute step records of 110 → 60 min), `workoutsMappedAndLimited` (exercise 10 days ago only in `loadWorkouts`), `snapshotWithoutSleepOrHeart` (empty lists → zones null, nightVitals null, no crash), `nightVitalsFromLastNight` (HR samples inside the last night only).
- [ ] **Step 2:** run `:core:jvmTest --tests "*HealthSnapshotBuilderTest"` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** run → PASS.
- [ ] **Step 5:** Commit `feat(core): build HealthSnapshot from raw platform records`.

### Task 3: Screen usage math and store support in core

**Files:**
- Create: `core/src/commonMain/kotlin/com/pulse/core/feature/ScreenUsage.kt`
- Modify: `core/src/commonMain/kotlin/com/pulse/core/store/ScreenTimeStore.kt`
- Test: `core/src/commonTest/kotlin/com/pulse/core/feature/ScreenUsageTest.kt`, extend `StoreTests.kt`

**Interfaces:**
- Produces:
  - `data class AppSession(val packageName: String, val label: String, val category: AppCategory, val start: Instant, val end: Instant)`
  - `data class ScreenUsageSnapshot(val days: List<ScreenTimeDay>, val hourlyToday: List<Int>, val appsToday: List<AppUsage>, val pickupsToday: Int, val firstPickupToday: String?)`
  - `object ScreenUsageMath { fun build(sessions: List<AppSession>, unlocks: List<Instant>, now: Instant, days: Int = 30): ScreenUsageSnapshot }` — minutes per day (sessions clipped to each day and to `now`), 24 hourly buckets for today, top apps today (minutes desc; icon/tint from category: SOCIAL `paperplane.fill`/CYAN, GAMES `gamecontroller`/PINK, BROWSER `safari`/BLUE, WORK `briefcase`/PURPLE, VIDEO `play.rectangle.fill`/RED, HEALTH `waveform.path.ecg`/GREEN, OTHER `app`/REST_ZONE), pickups = unlocks today, first pickup «HH:mm».
  - `ScreenTimeStore.applyUsage(snapshot: ScreenUsageSnapshot?)`; when set: `hasDetailedData = true`, `todayMinutes/yesterdayMinutes/week/daily/hourly/apps/pickups/firstPickup` come from it (demo values only for demo without usage); `pickups`/`firstPickup` become functions of state.
- [ ] **Step 1: Write failing tests** — `sessionsClippedToDayAndNow`, `hourlyBucketsToday`, `appsSortedByMinutes`, `pickupsAndFirstPickup`, store test `usageReplacesDemoNumbers`.
- [ ] **Step 2:** run → FAIL. **Step 3:** implement. **Step 4:** run all core tests → PASS (existing ScreenTime screens compile unchanged except the two renamed members). **Step 5:** commit `feat(core): real screen usage for ScreenTimeStore`.

### Task 4: Notifications on Android

**Files:**
- Create: `androidApp/src/main/java/com/pulse/android/platform/Notifications.kt` (channels, `PulseNotifications.post(reminder)`, permission check), `platform/ReminderScheduler.kt` (WorkManager: `schedule(reminder)` = unique one-time work named `reminder.id` with `ExistingWorkPolicy.REPLACE`, `cancel(id)`, `rescheduleAll(core, now)`), `platform/ReminderWorker.kt`, `platform/NotificationActionReceiver.kt` (`START_SLEEP`, `STOP_SLEEP`)
- Modify: `AndroidManifest.xml` (`POST_NOTIFICATIONS`, receiver), `build.gradle.kts` + `libs.versions.toml` (work), `MainActivity.kt` (open routes from notification extras `open=event:<id>|sleep|weekly|gameTest:<phase>`), `ui/main/MainScreen.kt` (handle pending open, show `GameTestSheet`), `ui/calendar/CalendarScreens.kt` (reschedule on save, cancel on delete), `ui/profile/ProfileScreen.kt` (toggle «Напоминания о событиях» with permission request; demo buttons «Пуш «Игровой тест до тренировки»», «Пуш «Скоро отбой» (начать сон)», «Пуш «Напоминание о событии»» — fire in 5 s), `PulseApplication.kt` (reschedule all on start)
- Channels: `events` «События», `tests` «Игровые тесты», `sleep` «Сон», `weekly` «Итоги недели».

- [ ] **Step 1:** Add dependency, channels, worker, scheduler; `rescheduleAll` schedules event reminders + game tests (Task 1) + bedtime + weekly.
- [ ] **Step 2:** Wire calendar save/delete and profile toggle/demo buttons; deep links from taps.
- [ ] **Step 3:** Build `:androidApp:assembleDebug`; on the emulator: grant notifications, press each demo button, verify the notification text, tap → correct screen, «Начать сон» action starts tracking.
- [ ] **Step 4:** Deny the permission → toggle stays off, no crash.
- [ ] **Step 5:** Commit `feat(android): local notifications — events, game tests, bedtime, weekly`.

### Task 5: Sleep tracking notification

**Files:**
- Create: `androidApp/src/main/java/com/pulse/android/platform/SleepTrackingNotification.kt`
- Modify: `PulseApplication.kt` (observe `core.sleep.state` → show/cancel), `NotificationActionReceiver.kt`

**Interfaces:** Consumes `NotificationRules.wakeCheck` (Task 1), `ReminderScheduler` (Task 4).

- [ ] **Step 1:** Ongoing notification on channel `sleep`: title «Сон отслеживается», `setUsesChronometer(true)`, `setWhen(start)`, text «Будильник в {wake}», action «☀️ Я проснулся» → `STOP_SLEEP`; schedule wake check; cancel both on stop.
- [ ] **Step 2:** Emulator: start sleep in the app → notification with running timer; «Я проснулся» saves the night (appears in «Сон» history); restart app with active session → notification restored.
- [ ] **Step 3:** Commit `feat(android): sleep tracking in an ongoing notification`.

### Task 6: Health Connect

**Files:**
- Create: `androidApp/src/main/java/com/pulse/android/platform/HealthConnectProvider.kt` (implements `HealthDataProvider`; reads records for 30 days into `HealthRawData`, calls `HealthSnapshotBuilder`), `platform/HealthConnectAvailability.kt`, `PermissionsRationaleActivity.kt` (shows the privacy text), debug-only `src/debug/.../HealthConnectSeeder.kt` + debug manifest with write permissions
- Modify: `AndroidManifest.xml` (read permissions for steps, distance, active calories, exercise, heart rate, resting HR, HRV, respiratory rate, oxygen saturation, weight, body fat, lean mass, sleep; `<queries>` for `com.google.android.apps.healthdata`; rationale activity + `VIEW_PERMISSION_USAGE` alias), `PulseApplication.kt` (`health = HealthConnectProvider(this)`), `ui/health/FitnessScreens.kt` `HealthSourceCard` (enable button; permission contract; install link when SDK unavailable), refresh on `ON_RESUME`, `core.sleep.applyHealth` after refresh, `ui/auth/LegalTexts.kt` (Health Connect paragraph already present — verify)
- Test: `androidApp/src/test/.../HealthConnectAvailabilityTest.kt` (SDK status → `INSTALLED / NEEDS_UPDATE / UNAVAILABLE` → card state); add `testImplementation(kotlin("test"))` to `androidApp/build.gradle.kts`

- [ ] **Step 1:** Write `HealthConnectAvailabilityTest` (pure mapping function `cardState(sdkStatus: Int): HealthCardState`) → FAIL → implement → PASS.
- [ ] **Step 2:** Provider + manifest + card; debug seeder fills 7 days of steps, a workout, 7 nights with stages and heart rate.
- [ ] **Step 3:** Emulator: seed, connect, grant → «Фитнес» and «Сон» show the seeded numbers (source «Health Connect»); deny sleep only → activity still shows.
- [ ] **Step 4:** Commit `feat(android): Health Connect — activity, workouts, sleep, heart`.

### Task 7: Real screen time

**Files:**
- Create: `androidApp/src/main/java/com/pulse/android/platform/UsageStatsReader.kt` (UsageEvents → `AppSession` + unlock instants; label/category from `PackageManager`/`ApplicationInfo.category`; own package excluded)
- Modify: `AndroidManifest.xml` (`PACKAGE_USAGE_STATS` with `tools:ignore="ProtectedPermissions"`), `ui/health/ScreenTimeScreens.kt` (settings sheet: «Доступ к статистике» opens `Settings.ACTION_USAGE_ACCESS_SETTINGS`; switch reflects `AppOpsManager` state; `ManualScreenTimeInfo` only when access is off), `PulseApplication`/screen refresh on resume → `core.screenTime.applyUsage(...)`

- [ ] **Step 1:** Reader + access check + wiring.
- [ ] **Step 2:** Emulator: grant usage access, open a few apps, return → today/hourly/apps are real; revoke → manual mode again.
- [ ] **Step 3:** Commit `feat(android): real screen time from usage statistics`.

### Task 8: Widget and Quick Settings tile

**Files:**
- Create: `androidApp/src/main/java/com/pulse/android/widget/SleepWidget.kt` (Glance: last night duration + score, button «Начать сон» / timer + «Я проснулся»), `widget/SleepWidgetReceiver.kt`, `res/xml/sleep_widget_info.xml`, `platform/SleepTileService.kt` (QS tile «Сон»: active state = tracking; tap toggles)
- Modify: `AndroidManifest.xml`, `build.gradle.kts` (glance), `PulseApplication.kt` (update widget + tile on sleep state change)

- [ ] **Step 1:** Widget + tile.
- [ ] **Step 2:** Emulator: add widget, start/stop from widget and tile; app state follows; widget updates after stop.
- [ ] **Step 3:** Commit `feat(android): sleep widget and quick settings tile`.

### Task 9: Review and docs

- [ ] **Step 1:** `superpowers:requesting-code-review` + `security-reviewer` over the step-4 diff (permissions, exported components, PendingIntent flags `FLAG_IMMUTABLE`, no health data in logs).
- [ ] **Step 2:** Fix findings; all core tests pass; `assembleRelease` builds with R8.
- [ ] **Step 3:** `docs/ANDROID.md` row 4 ✅ with notes; commit `docs: mark Android step 4 done`.
