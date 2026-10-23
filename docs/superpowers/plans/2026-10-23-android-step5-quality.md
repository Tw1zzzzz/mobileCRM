# Android step 5 — quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Android app is clean under lint, usable with TalkBack (iOS labels, real roles and states), keeps its place through rotation/recreation and process death, follows permission changes made in system settings, and lays out correctly on small phones with large fonts and on tablets/foldables.

**Architecture:** Most fixes go into the design-system components (`ui/components/Components.kt`, `DesignSystem.kt`, `Inputs.kt`) so every screen inherits them; screen files only add the labels iOS has. Behaviour is pinned by JVM Compose UI tests (Robolectric, SDK 34 sandbox — SDK 35+ sandboxes need Java 21, the project runs on Java 17) plus pure unit tests; what the JVM cannot show (real density, font scale, rotation, process death) is checked on the headless emulator.

**Tech Stack:** Kotlin 2.4, Compose BOM 2026.09.00, `org.robolectric:robolectric:4.17`, `androidx.compose.ui:ui-test-junit4` (+ `ui-test-manifest` in debug), Android Lint (AGP 9.4.1).

**Spec:** `docs/ANDROID.md` row 5 («Качество: разные экраны и устройства, фон, разрешения, TalkBack (галочки согласий при регистрации — без роли «флажок»)»); iOS accessibility labels (`grep -rn accessibility Pulse/`) as the copy source; deferred minors #6, #8, #12, #13 from the step 4 final review.

## Global Constraints

- minSdk 28, targetSdk 36, compileSdk 37; all copy Russian; TalkBack labels verbatim from iOS wherever iOS has one.
- Phones stay portrait (iOS ships `UIInterfaceOrientationPortrait`, iPhone only). On large screens (smallest width ≥ 600dp) Android 16 ignores the lock — there the app must work in any orientation and survive recreation.
- No new runtime dependencies; test-only dependencies are fine.
- The emulator always runs headless (`-no-window`); never open a window.
- `:androidApp:lintDebug` passes with 0 errors and 0 warnings; every suppression lives in `androidApp/lint.xml` with a comment giving the reason.
- Robolectric tests use `@Config(application = android.app.Application::class, sdk = [34])` so `PulseApplication` (WorkManager, Keystore, Health Connect) never starts in tests.

## Review Focus

- A tablet/foldable is rotated (or the window resized) while Сон → Восстановление is open → after recreation the same tab and screen are shown. Test: `routerRoundTripsThroughSavedState` (Task 4) + rotation on the emulator (Task 7).
- Registration with TalkBack → each consent is announced as a checkbox with its state; double-tap toggles it; the document link is a separate control. Test: `consentRowsAreCheckboxes` (Task 2).
- Notifications switched off in system settings while Pulse is in the background → back in Profile the reminders switch shows off. Test: `remindersSwitchFollowsSystemSetting` (Task 5).
- Sign out / switch account → notifications already posted for the previous account disappear (deferred #6). Test: `accountChangeClearsPostedNotifications` (Task 5).
- A 360dp phone at font scale 2.0 → tab bar, buttons, card titles stay readable (wrap, no clipped words). Emulator checklist in Task 7; every defect found gets fixed in Task 7.

---

### Task 1: Lint clean and part of the check

**Files:**
- Create: `androidApp/lint.xml`
- Modify: `androidApp/build.gradle.kts` (`lint { lintConfig = file("lint.xml"); abortOnError = true; warningsAsErrors = true }`)
- Modify: `ui/components/RadarChart.kt:63`, `ui/health/ScreenTimeScreens.kt:462` (`BoxWithConstraints` whose scope is unused → `Box`)
- Modify: the 10 `UseKtx` sites from `androidApp/build/reports/lint-results-debug.txt` (`SharedPreferences.edit {}`, `String.toUri()`, `Bitmap.scale()`), `platform/Notifications.kt:33` (`ObsoleteSdkInt`: drop the `SDK_INT >= O` check)
- Modify: `src/debug/AndroidManifest.xml:12` — seeder receiver gets `android:permission="android.permission.DUMP"` (deferred #8: only `adb shell` holds DUMP, the documented seed command keeps working)
- Modify: `docs/ANDROID.md` build block — add `./gradlew :androidApp:lintDebug`

`lint.xml` ignores, each with its reason as an XML comment:
- `StateFlowValueCalledInComposition` — screens call `observeCore()` first, which subscribes to every store flow; `.value` reads after it recompose correctly (by design, see `ui/AppScope.kt`).
- `LockedOrientationActivity`, `DiscouragedApi` — portrait on phones as on iOS; large screens are handled in Task 6.
- `OldTargetApi` — raising targetSdk to 37 is a step 6 (Google Play) decision.
- `UnusedAttribute` — widget `targetCellWidth/Height` are read on API 31+, ignored below.
- `AppBundleLocaleChanges` — `RussianLocale` only formats pickers; no language download.
- `DataExtractionRules` — `allowBackup="false"`; there is nothing to back up.

- [ ] **Step 1:** `./gradlew :androidApp:lintDebug` → FAIL, «35 errors, 19 warnings».
- [ ] **Step 2:** Add `lint.xml`, the build config and the code fixes above.
- [ ] **Step 3:** `./gradlew :androidApp:lintDebug` → BUILD SUCCESSFUL; `lint-results-debug.txt` says «No issues found».
- [ ] **Step 4:** `./gradlew :core:allTests :androidApp:testDebugUnitTest :androidApp:assembleDebug -q` → no output; then the seed broadcast from `docs/ANDROID.md` still logs «Seeded Health Connect» on the emulator.
- [ ] **Step 5:** Commit `chore(android): lint clean, lint in the build check`.

### Task 2: Compose UI test harness + consent checkboxes

**Files:**
- Modify: `gradle/libs.versions.toml` (`robolectric = "4.17"`; libraries `robolectric`, `androidx-compose-ui-test-junit4` = `androidx.compose.ui:ui-test-junit4`, `androidx-compose-ui-test-manifest` = `androidx.compose.ui:ui-test-manifest`, both versioned by the BOM)
- Modify: `androidApp/build.gradle.kts` (`testOptions { unitTests.isIncludeAndroidResources = true }`; `testImplementation(libs.robolectric)`, `testImplementation(platform(libs.androidx.compose.bom))`, `testImplementation(libs.androidx.compose.ui.test.junit4)`, `debugImplementation(libs.androidx.compose.ui.test.manifest)`)
- Create: `androidApp/src/test/java/com/pulse/android/ui/UiTestSupport.kt`
- Create: `androidApp/src/test/java/com/pulse/android/ui/auth/ConsentTest.kt`
- Modify: `ui/auth/AuthScreen.kt:280-292` (`Consent`)

**Interfaces:**
- Produces (in `UiTestSupport.kt`):
  - `fun testCore(role: UserRole?): PulseCore` — `JvmFileSystem` in a fresh temp dir, `MemorySecretStore()`, `HttpClient(OkHttp)`, `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`, `health = null`; when `role != null`, `runBlocking { core.session.switchToDemo(role) }`.
  - `fun ComposeContentTestRule.setMain(core: PulseCore, route: String? = null)` — `setContent { MaterialTheme(darkColorScheme(...as MainActivity)) { MainScreen(core, core.session.user.value!!, debugRoute = route) } }`.
  - `fun SemanticsNodeInteractionsProvider.assertAccessible()` — over the merged tree, every node with `OnClick` or a `ToggleableState`: (a) has non-blank `Text`, `EditableText` or `ContentDescription`, else fails naming the node's bounds and its parent's text; (b) if it has no `Text`, its touch bounds are ≥ 44dp × 44dp (iOS minimum; icon-only controls).

- [ ] **Step 1: Write the failing test** `ConsentTest.consentRowsAreCheckboxes`: `setContent { AuthScreen(testCore(null).session) }`, switch to registration via its link, then
  - `onAllNodes(isToggleable()).assertCountEquals(2)`
  - `onNode(isToggleable() and hasText("Я принимаю условия", substring = true)).assertIsOff().performClick().assertIsOn()`
  - the same node `assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))`
  - `onNode(hasText("Пользовательского соглашения") and hasClickAction() and !isToggleable()).assertExists()`
  - `assertAccessible()` on the registration screen.
- [ ] **Step 2:** `./gradlew :androidApp:testDebugUnitTest --tests "*ConsentTest"` → FAIL (`assertCountEquals(2)`: 0 toggleable nodes). First run downloads the SDK 34 sandbox (~10 min, run in background).
- [ ] **Step 3:** Rebuild `Consent(prefix, link, checked, onLink, onCheck)`: an inner `Row` with `Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = onCheck)` holding the box (icon description `null`, no own click) and the prefix; the link `Text` sits outside it with `clickable(onClickLabel = "Открыть", role = Role.Button)`. Toggle row min height 44dp.
- [ ] **Step 4:** Same command → PASS; full `:androidApp:testDebugUnitTest` → all pass.
- [ ] **Step 5:** Commit `fix(android): consents are real checkboxes for TalkBack` (harness included).

### Task 3: TalkBack labels, roles and states (iOS parity)

**Files:**
- Modify: `ui/components/Components.kt` — `fun Modifier.pressable(enabled: Boolean = true, role: Role? = Role.Button, label: String? = null, onClick: () -> Unit): Modifier` (passes `role` and `onClickLabel = label` to `clickable`); `ChipButton` → `selectable(isSelected, role = Role.Tab)`; `SectionHeader` title → `semantics { heading() }`
- Modify: `ui/components/DesignSystem.kt` — `PulseSegmented` / `PulseScrollTabs` items → `selectable(selected, role = Role.Tab)`; `NavHeader` title → `heading()`; `SheetScaffold` close button label «Закрыть»; `NavHeader` back «Назад»
- Modify: `ui/components/Inputs.kt` — `PulseSlider` with blank title takes a `label` parameter for semantics
- Modify: `ui/main/MainScreen.kt` (tab items `selectable(role = Role.Tab)`, «Быстрое добавление»), and the screens below
- Test: `androidApp/src/test/java/com/pulse/android/ui/AccessibilitySweepTest.kt`

Labels to add (Android site ← iOS source, copy verbatim): «Создать событие» (Calendar), «Редактировать» (event), «Форма дня {score} из 100, {label}» / «Серия заполнения: {n} дней» / «Календарь» or «Календарь, есть события сегодня» / «Уведомления» / «Аккаунт» (Today header), «Показать пароль» / «Скрыть пароль» (auth), «Поделиться» (Sleep), «Настройки сна» (Recovery), «Фазы сна с {HH:mm} до {HH:mm}» (hypnogram, children merged), «ELO {elo}, K/D {kd 2 digits}» / «Настройки Faceit» / «Уровень {n}» (Game), «Редактировать профиль» (Profile), «Настройки экранного времени» (Screen time), «Добавить: {title}» / «В избранное» / «Убрать из избранного» / «Редактировать» / «Видео вариации {n}» / «Удалить вариацию {n}» (Lineups), «Календарь» (Fitness week), «Записать тренировку» + click label «Оценить тяжесть тренировки» (Fitness), «История питания» (Nutrition), «Предыдущая неделя» / «Следующая неделя» (Weekly), «Меньше» / «Больше» (Onboarding), «Изменить анкету» (Player portrait), «Настройки Health Connect» (iOS «Настройки Apple Health»), «Статус профиля: {role}» (Team), RPE slider «Тяжесть».

- [ ] **Step 1: Write the failing tests** in `AccessibilitySweepTest`: one test per screen, each `rule.setMain(testCore(role), route)` then `assertAccessible()` — routes `today`, `state`, `diary`, `profile`, `sleep`, `recovery`, `fitness`, `screen`, `calendar`, `weekly`, `stats`, `gameStats`, `portrait` (role `TEAM_PLAYER`) and `players`, `analytics`, `player` (staff role); plus `tabsReportSelection` (`onNode(hasText("Сегодня") and isSelectable()).assertIsSelected()`, other tabs `assertIsNotSelected()`), `headersAreHeadings` (route `sleep`: `onNode(hasText("Сон") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()`).
- [ ] **Step 2:** `./gradlew :androidApp:testDebugUnitTest --tests "*AccessibilitySweepTest"` → FAIL; the messages list the unlabeled controls per screen.
- [ ] **Step 3:** Component changes first, then the labels above, re-running until the sweep is green. A control the sweep flags that iOS has no label for gets the visible title of what it opens (ruling, ledgered).
- [ ] **Step 4:** Same command → PASS; full unit suite → PASS; `lintDebug` → PASS.
- [ ] **Step 5:** Commit `feat(android): TalkBack labels, roles and headings as on iOS`.

### Task 4: Navigation survives recreation and process death

**Files:**
- Modify: `ui/main/Navigation.kt` (`RouteCodec`, `AppRouter.saved()`, `AppRouter.Companion.restored()`, `AppRouter.Saver`)
- Modify: `ui/main/MainScreen.kt:70-72` (`rememberSaveable(saver = AppRouter.Saver) { AppRouter() }`; the debug-route effect runs only when nothing was restored)
- Modify: `ui/auth/AuthScreen.kt:185` (`RegistrationForm` kept with `rememberSaveable` — passwords excluded)
- Test: `androidApp/src/test/java/com/pulse/android/ui/main/AppRouterSavingTest.kt` (plain JVM), `androidApp/src/test/java/com/pulse/android/ui/auth/RegistrationRestoreTest.kt` (Robolectric, `StateRestorationTester`)

**Interfaces:**
- Produces:
  - `object RouteCodec { fun encode(route: Route): String; fun decode(text: String): Route? }` — data objects by simple name (`"Sleep"`), `Calendar` → `"Calendar:true"`/`"Calendar:false"`, `Event`/`Player`/`Lineup` → `"Event:<uuid>"`; unknown text → `null`.
  - `fun AppRouter.saved(): List<String>` — first element `selectedTab.name`, then one `"TAB=enc|enc"` per non-empty stack.
  - `AppRouter` gains a `companion object` with `fun restored(saved: List<String>): AppRouter` and `val Saver: Saver<AppRouter, List<String>>`. Survey/food flags are not saved (they open sheets).
- [ ] **Step 1: Write failing tests** — `everyRouteRoundTrips` (every `Route` subtype incl. `Calendar(true)`, `Event(uuid)`, `Player(uuid)`, `Lineup(uuid)`), `unknownRouteIsDropped` (`decode("Nope") == null`; a saved stack `"STATE=Sleep|Nope"` restores as `[Sleep]`), `routerRoundTripsThroughSavedState` (tab `STATE`, `STATE=[Sleep, SleepRecovery]`, `PROFILE=[PlayerPortrait]` → same `selectedTab` and paths), `registrationKeepsFieldsButNotPasswords` (type nickname «Tw1z», email, password; `emulateSavedInstanceStateRestore()`; nickname and email still shown, password fields empty).
- [ ] **Step 2:** run both test classes → FAIL (unresolved `RouteCodec`; registration fields empty after restore).
- [ ] **Step 3:** Implement the interfaces; registration saver stores the form without `password`/`confirmPassword`.
- [ ] **Step 4:** run → PASS; full suite → PASS.
- [ ] **Step 5:** Commit `fix(android): keep the open screen across rotation and process death`.

### Task 5: Permissions changed in system settings; notifications follow the account

**Files:**
- Modify: `ui/profile/ProfileScreen.kt:~240-260` (reminders switch shows `PulseNotifications.isEnabled(context) && PulseNotifications.systemAllows(context)`, re-read in `LifecycleResumeEffect`; label stays «Напоминания о событиях» as on iOS)
- Modify: `platform/Notifications.kt` — `fun onAccountChanged(context: Context)`: `NotificationManagerCompat.from(context).cancelAll()`
- Modify: `PulseApplication.kt` user collector — call `PulseNotifications.onAccountChanged` when the previous emitted user id is non-null and differs from the new one (the sleep notification is re-posted by the sleep collector if the new account is tracking)
- Test: `androidApp/src/test/java/com/pulse/android/platform/NotificationsAccountTest.kt`, `androidApp/src/test/java/com/pulse/android/ui/profile/RemindersSwitchTest.kt` (both Robolectric)

- [ ] **Step 1: Write failing tests** — `accountChangeClearsPostedNotifications` (`ensureChannels`, post one `ScheduledReminder` via `PulseNotifications.post`, `onAccountChanged(context)` → `shadowOf(notificationManager).allNotifications` empty); `remindersSwitchFollowsSystemSetting` (`setMain(testCore(TEAM_PLAYER), "profile")`, pref enabled; `shadowOf(notificationManager).setNotificationsEnabled(false)`, resume → the switch node next to «Напоминания о событиях» `assertIsOff()`; enabled again + resume → `assertIsOn()`).
- [ ] **Step 2:** run → FAIL (`onAccountChanged` unresolved; switch on while system blocks).
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** run → PASS; full suite → PASS. Emulator: reminders on → `adb shell pm revoke com.pulsecrm.mvp android.permission.POST_NOTIFICATIONS` while backgrounded → reopen Profile → switch off.
- [ ] **Step 5:** Commit `fix(android): reminders switch follows system settings; account change clears notifications`.

### Task 6: Large screens — capped content width

**Files:**
- Modify: `ui/theme/Theme.kt` (`Metrics.maxContentWidth = 600.dp`)
- Modify: `ui/components/DesignSystem.kt` (`ScreenScaffold`, `SheetScaffold`: content `Modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth()` centered), `ui/main/MainScreen.kt` (tab bar capped and centered the same way), `ui/auth/AuthScreen.kt` (form column capped)
- Test: `androidApp/src/test/java/com/pulse/android/ui/LayoutWidthTest.kt` (Robolectric, qualifiers per test)

- [ ] **Step 1: Write failing tests** — `contentIsCappedOnWideScreens` (`@Config(qualifiers = "w1000dp-h700dp-land")`, `setMain(..., "today")`: the node with text «Сегодня» in the header lies inside a column whose width ≤ 600dp and whose centre is within 1dp of the window centre — use a `testTag("screenContent")` on the scaffold column), `phoneUsesFullWidth` (`w360dp-h740dp`: `screenContent` width == 360dp minus the scaffold's horizontal padding).
- [ ] **Step 2:** run → FAIL (width 1000dp minus padding).
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** run → PASS; full suite → PASS.
- [ ] **Step 5:** Commit `fix(android): content width capped on tablets and foldables`.

### Task 7: Screens, fonts, rotation, background — emulator pass

**Files:** whatever the checklist below turns up (each defect: fix, re-check, ledger line). No new code without a defect.

Setup and reset (headless emulator `PulsePixel`):
- small phone + big font: `adb shell wm size 720x1280; adb shell wm density 320; adb shell settings put system font_scale 2.0`
- tablet: `adb shell wm size 2560x1600; adb shell wm density 320; adb shell settings put system font_scale 1.0` (sw 800dp → orientation unlocked); rotate with `adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1`
- reset: `adb shell wm size reset; adb shell wm density reset; adb shell settings put system font_scale 1.0; adb shell settings put system user_rotation 0`

- [ ] **Step 1:** Small phone, font 2.0 — screenshot and inspect: login, registration (consents), Сегодня, Состояние, Дневник, Профиль, Сон, Восстановление (sheet «Окно сна»), Фитнес, Экранное время, Календарь (+ create sheet), Итоги недели, tab bar with «+». Defect = clipped/overlapping text, unreachable button, horizontal overflow.
- [ ] **Step 2:** Tablet landscape — same list; content centred ≤ 600dp; rotate portrait↔landscape on Сон → Восстановление with the «Окно сна» sheet open → same screen afterwards (sheet may close).
- [ ] **Step 3:** Process death — open Сон → Восстановление, HOME, `adb shell am kill com.pulsecrm.mvp` (if the process survives, `run-as … kill`), reopen from recents → same tab and screen.
- [ ] **Step 4:** TalkBack semantics on the device — `uiautomator dump` on registration: both consents `checkable="true"` with `checked` toggling on tap; Today header buttons have `content-desc` «Уведомления», «Аккаунт».
- [ ] **Step 5:** Reset the emulator display; fixes committed per defect as `fix(android): <what> on <screen size/font>`.

### Task 8: Usage sessions with several resumed activities (deferred #12)

**Files:**
- Modify: `platform/UsageEventsParser.kt` (`RawUsageEvent` gains `className: String = ""`; a package's session closes only when none of its activities is still resumed), `platform/UsageStatsReader.kt` (pass `event.className`)
- Test: `androidApp/src/test/java/com/pulse/android/platform/UsageEventsParserTest.kt`

- [ ] **Step 1: Write failing test** `pauseOfOneActivityKeepsPackageOpen`: yt `A` RESUMED 12:00, yt `B` RESUMED 12:10, yt `A` PAUSED 12:15, yt `B` PAUSED 12:40 → one session 12:00–12:40 (today it ends 12:15).
- [ ] **Step 2:** `--tests "*UsageEventsParserTest"` → FAIL.
- [ ] **Step 3:** Implement (resumed class set per package; another package's RESUMED still closes all open sessions, as today).
- [ ] **Step 4:** run → PASS (all 7); full suite → PASS.
- [ ] **Step 5:** Commit `fix(android): screen time with several resumed activities`.

### Task 9: Wrap-up

- [ ] **Step 1:** `./gradlew :core:allTests :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug -q` → no output; `./gradlew :androidApp:assembleRelease --no-configuration-cache -q` → no output.
- [ ] **Step 2:** `docs/ANDROID.md`: row 5 ✅ with a one-line note; build block mentions that UI tests run on Robolectric SDK 34 (Java 17) and that the first run downloads the sandbox.
- [ ] **Step 3:** Commit `docs(android): step 5 done`; push `android`.

Deferred on purpose (not in this plan): the widget's stale night until the app opens (needs background Health Connect reads — new permission); body metrics older than 30 days (needs `READ_HEALTH_DATA_HISTORY`, a Play declaration — step 6); a 1-minute manual session shown as last night (same as iOS `displayNight`).
