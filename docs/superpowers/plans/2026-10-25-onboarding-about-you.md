# Onboarding step «О тебе» Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A mandatory questionnaire step «О тебе» (birth date, height, weight, bedtime/wake, daily targets) on iOS and Android whose answers feed heart-rate zones, kcal estimates, body metrics, sleep reminders and daily goals.

**Architecture:** One model + math + store per platform, identical rules and JSON: Kotlin in `core/` (Android), Swift in `Pulse/` (iOS twin). Answers are applied through one call (`applyAboutYou`) that writes the body profile and the existing sleep window, fitness/nutrition goals and screen-time limit. Health snapshot building takes the body profile for age and weight. UI: a new step in the existing onboarding flow, an «about only» mode for existing players, a profile row to edit body data.

**Tech Stack:** Kotlin 2.4 + kotlinx-datetime/serialization (core), Jetpack Compose + Robolectric tests (Android), SwiftUI + Swift Testing (iOS), HealthKit, Health Connect.

**Spec:** `docs/superpowers/specs/2026-10-25-onboarding-about-you-design.md`

## Global Constraints

- Both platforms: same copy, same rules, same JSON (`body-profile.json`: `{"birthDate":"2004-03-12","heightCm":182,"weightKg":74.0,"updatedAt":"2026-10-25T10:00:00Z"}`; ISO dates).
- All fields mandatory, no skip; sex is never asked; staff never see the step; demo accounts never see it.
- Ranges: age 10–80 years; height 120–230 cm; weight 30.0–200.0 kg (one decimal). Goals use the existing editors' ranges: active minutes 10–180; steps 2 000–30 000 step 500; active kcal 100–2 000; food kcal 1 200–4 500; water 1 000–5 000 ml step 250; screen limit 1–12 h whole hours.
- Max heart rate formula stays Tanaka (`FitnessMath.maxHeartRate`), only its age input changes.
- Suggestions for a **new** player: food kcal = round(weight × 33 / 50) × 50, water ml = round(weight × 33 / 250) × 250, both clamped to their ranges, recalculated on weight change until the player edits that field. Players who already finished the survey get their current goals, never recalculated.
- Weight shown/used: the Health platform's latest weight if its measurement time is after `BodyProfile.updatedAt`, otherwise the profile weight. Age: profile birth date; on iOS Apple Health's date of birth only when there is no profile.
- Body metrics order: «Вес» (кг), «Рост» (см), «ИМТ» (no unit, one decimal = weight / (height/100)²), then «Жир», «Сух. масса» when the Health platform has them.
- Demo profile (player and solo demos): birth date 2004-03-12, 182 cm, 74.0 kg, `updatedAt` = distant past.
- Android: step 5 rules — TalkBack labels, 44dp touch targets, text fits 360dp at 130%, form survives rotation; new screens go into `AccessibilitySweepTest`/`TextFitSweepTest`.

Copy (verbatim on both platforms):
- Step title «О тебе», subtitle «Возраст, рост и вес нужны для пульсовых зон и калорий, режим — для напоминаний о сне. Всё можно поменять в профиле.»
- Sections «Тело», «Режим сна», «Цели на день».
- Labels: «Дата рождения», «Рост, см», «Вес, кг», «Отбой», «Подъём», «Активные минуты в день», «Шаги в день», «Активные ккал в день», «Калории в день», «Вода в день, л», «Лимит экранного времени, ч».
- Errors: «Укажи дату рождения», «Рост — от 120 до 230 см», «Вес — от 30 до 200 кг», «Подъём не может совпадать с отбоем».
- Step counters: «Шаг 1 из 4» … «Шаг 4 из 4» (goals, player, about, test). Button «Дальше» (disabled while any error). About-only mode button «Сохранить», link «Выйти из аккаунта».
- Profile settings row «О тебе» (icon `person.text.rectangle`), sheet title «О тебе», fields «Тело» only, button «Сохранить».

## Review Focus

- A player types height «18» while typing «182» → no error flashes mid-typing over the keyboard and «Дальше» stays disabled until valid; error shows once the field loses focus or on «Дальше». Test: `aboutStepRequiresAllFields` (Task 4).
- Existing player with a custom 3 000 kcal goal opens the about-only step → kcal still 3 000 after saving. Test: `existingPlayerKeepsCurrentGoals` (Task 1) + `applyAboutYouWritesEveryMetric` (Task 2).
- Bedtime 23:30, wake 23:30 → blocked with «Подъём не может совпадать с отбоем»; bedtime 01:00, wake 09:00 (after midnight) → accepted. Test: `wakeEqualToBedtimeIsAnError` (Task 1).
- Health Connect / Apple Health weight older than the questionnaire → the typed weight wins; newer → Health wins. Test: `newerHealthWeightWins`, `olderHealthWeightLoses` (Task 1, Swift twin Task 5).
- A player rotates the phone/tablet mid-step or the process dies → typed values survive (Android). Test: `aboutStepSurvivesRecreation` (Task 4).

---

### Task 1: Core model and math (Kotlin)

**Files:**
- Create: `core/src/commonMain/kotlin/com/pulse/core/model/BodyProfile.kt`
- Test: `core/src/commonTest/kotlin/com/pulse/core/model/AboutYouTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class BodyProfile(val birthDate: LocalDate, val heightCm: Int, val weightKg: Double, val updatedAt: Instant)` with `companion object { val demo: BodyProfile }` (values from Global Constraints, `updatedAt = Instant.DISTANT_PAST`).
  - `object BodyMath { fun bmi(weightKg: Double, heightCm: Int): Double; fun currentWeight(profile: BodyProfile?, healthKg: Double?, healthAt: Instant?): Double?; fun metrics(profile: BodyProfile?, healthKg: Double?, healthAt: Instant?, fatPercent: Double?, leanKg: Double?): List<BodyMetric> }` — `bmi` rounded to one decimal.
  - `data class AboutYouForm(val birthDate: LocalDate? = null, val heightText: String = "", val weightText: String = "", val bedtimeHour: Int, val bedtimeMinute: Int, val wakeHour: Int, val wakeMinute: Int, val activeMinutes: Int, val steps: Int, val activeKcal: Int, val foodKcal: Int, val waterMl: Int, val screenLimitHours: Int, val suggestsFromWeight: Boolean, val kcalEdited: Boolean = false, val waterEdited: Boolean = false)` with `val heightCm: Int?`, `val weightKg: Double?` (parse, comma or dot), `fun withWeightText(text: String): AboutYouForm` (re-suggests kcal/water when `suggestsFromWeight` and not edited), `fun withFoodKcal(value: Int)`, `fun withWater(ml: Int)` (set edited flags).
  - `enum class AboutYouField { BIRTH_DATE, HEIGHT, WEIGHT, WAKE }`
  - `object AboutYou { val AGE_YEARS = 10..80; val HEIGHT_CM = 120..230; val WEIGHT_KG = 30.0..200.0; fun suggestedKcal(weightKg: Double): Int; fun suggestedWaterMl(weightKg: Double): Int; fun errors(form: AboutYouForm, now: Instant): Map<AboutYouField, String>; fun initial(window: SleepWindow, fitness: FitnessGoals, nutrition: NutritionGoals, screenLimitMinutes: Int, profile: BodyProfile?, isNewPlayer: Boolean): AboutYouForm; fun profile(form: AboutYouForm, now: Instant): BodyProfile? }` (`profile` returns null when errors exist). Goal ranges as `AboutYou.ACTIVE_MINUTES = 10..180` etc. from Global Constraints. Age uses `FitnessMath.age(birthDate, now)`.

- [ ] **Step 1: Write failing tests** in `AboutYouTest` (use `pinTestZone()` like other model tests):
  - `bmiFromWeightAndHeight`: `BodyMath.bmi(74.0, 182) == 22.3`.
  - `suggestionsFromWeight`: `suggestedKcal(74.0) == 2450`, `suggestedWaterMl(74.0) == 2500`, `suggestedKcal(20.0) == 1200` (clamped), `suggestedWaterMl(200.0) == 5000`.
  - `newPlayerKcalFollowsWeightUntilEdited`: initial(isNewPlayer = true) → `withWeightText("74")` gives kcal 2450; `withFoodKcal(2600)` then `withWeightText("80")` keeps 2600 while water becomes 2750.
  - `existingPlayerKeepsCurrentGoals`: `initial(..., nutrition = NutritionGoals(kcal = 3000, waterMilliliters = 3000), isNewPlayer = false).withWeightText("74")` → foodKcal 3000, waterMl 3000.
  - `missingFieldsAreErrors`: empty form → errors for BIRTH_DATE «Укажи дату рождения», HEIGHT «Рост — от 120 до 230 см», WEIGHT «Вес — от 30 до 200 кг»; `profile(form, now) == null`.
  - `rangesAreChecked`: height "119"/"231" → HEIGHT error; "120"/"230" → none; weight "29,9" → error, "74,5" → `weightKg == 74.5`, none; birth date making age 9 → BIRTH_DATE error.
  - `wakeEqualToBedtimeIsAnError`: 23:30/23:30 → WAKE «Подъём не может совпадать с отбоем»; 01:00/09:00 → no WAKE error.
  - `newerHealthWeightWins` / `olderHealthWeightLoses`: profile updatedAt T; health 76.0 at T+1d → 76.0; at T−1d → profile 74.0; profile null → health; health null → profile.
  - `metricsOrder`: `metrics(profile 74/182, null, null, 14.0, 60.0)` titles `["Вес","Рост","ИМТ","Жир","Сух. масса"]`, values `[74.0, 182.0, 22.3, 14.0, 60.0]`.
  - `profileJsonShape`: `Json.encodeToString(BodyProfile.serializer(), BodyProfile(LocalDate(2004,3,12), 182, 74.0, Instant.parse("2026-10-25T10:00:00Z")))` equals the JSON in Global Constraints.
- [ ] **Step 2:** `./gradlew :core:jvmTest --tests "*AboutYouTest"` → FAIL (unresolved references).
- [ ] **Step 3:** Implement `BodyProfile.kt`.
- [ ] **Step 4:** Same command → PASS; `./gradlew :core:allTests -q` → PASS.
- [ ] **Step 5:** Commit `feat(core): body profile and «О тебе» form rules`.

### Task 2: Core store and wiring (Kotlin)

**Files:**
- Create: `core/src/commonMain/kotlin/com/pulse/core/store/BodyProfileStore.kt`
- Modify: `core/src/commonMain/kotlin/com/pulse/core/PulseCore.kt` (store, `activate`, `applyAboutYou`, `needsAboutYou`), `core/src/commonMain/kotlin/com/pulse/core/store/FitnessStore.kt` (`bodyProfile` property, RPE age, `bodyMetrics` fallback)
- Test: `core/src/commonTest/kotlin/com/pulse/core/store/StoreTests.kt` (new test class `BodyProfileStoreTests`), `core/src/jvmTest/kotlin/com/pulse/core/storage/PulseCoreTests.kt`

**Interfaces:**
- Consumes: Task 1 types.
- Produces:
  - `class BodyProfileStore(storage: JsonFileStorage<BodyProfile>?) : PulseStore<BodyProfile?>` with `val profile: BodyProfile?`, `fun reload(user: User?)` (saved → it; demo → `BodyProfile.demo`; else null), `fun save(profile: BodyProfile)`, `companion object { const val FILE = "body-profile.json" }`.
  - `PulseCore.body: BodyProfileStore`; `PulseCore.needsAboutYou: Boolean` (signed-in, not staff, not demo, `body.profile == null`); `fun PulseCore.applyAboutYou(form: AboutYouForm, now: Instant = Clock.System.now()): Boolean` — false when `AboutYou.profile` is null; otherwise saves profile, `sleep.updateWindow(window.copy(bedtime…, wake…))`, `fitness.updateGoals(FitnessGoals(activeMinutes, steps, activeKcal))`, `nutrition.updateGoals(nutrition.goals.copy(kcal = foodKcal, waterMilliliters = waterMl))`, `screenTime.setLimit(screenLimitHours * 60)`.
  - `FitnessStore.bodyProfile: BodyProfile?` (set by PulseCore after every reload and save); RPE uses `FitnessMath.maxHeartRate(FitnessMath.age(bodyProfile?.birthDate))`; `bodyMetrics` = `health?.bodyMetrics ?: bodyProfile?.let { BodyMath.metrics(it, null, null, null, null) } ?: (demo baseline or empty)`.
- [ ] **Step 1: Write failing tests** — `demoAccountsHaveAProfile` (reload demo user → `BodyProfile.demo`), `realAccountStartsWithout` (→ null), `needsAboutYouOnlyForRealPlayers` (real player true; staff false; demo false; after `applyAboutYou` false), `applyAboutYouWritesEveryMetric` (valid form 23:30/07:30, 45 min, 8 500 steps, 500 kcal, 2 600 food kcal, 2 750 ml, 4 h → `sleep.window` 23:30/07:30, `fitness.goals == FitnessGoals(45, 8500, 500)`, nutrition kcal 2600 & water 2750 with protein unchanged, `screenTime.state.value.dailyLimitMinutes == 240`, `body.profile` saved, file `body-profile.json` written in the account folder), `invalidFormChangesNothing` (empty form → false, all goals unchanged), `bodyMetricsFromProfileWithoutHealth` (real player with profile, no health → titles Вес/Рост/ИМТ).
- [ ] **Step 2:** `./gradlew :core:jvmTest --tests "*BodyProfile*" --tests "*PulseCore*"` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same → PASS; `:core:allTests` → PASS.
- [ ] **Step 5:** Commit `feat(core): «О тебе» feeds sleep window, goals and body metrics`.

### Task 3: Health snapshot uses the profile (Kotlin + Android provider)

**Files:**
- Modify: `core/.../feature/HealthSnapshotBuilder.kt` (`HealthRawData.weightAt: Instant? = null`; `build(raw, now, sourceName, body: BodyProfile? = null)`: age from `body?.birthDate`, kcal estimate and metrics via `BodyMath`), `core/.../store/FitnessStore.kt` (`HealthDataProvider.snapshot(now: Instant, body: BodyProfile?)`, `refreshHealth` passes `bodyProfile`), `androidApp/.../platform/HealthConnectProvider.kt` (passes `body`, sets `weightAt` from the latest weight record time)
- Test: `core/.../feature/HealthSnapshotBuilderTest.kt`, `StoreTests.kt` (`FakeHealth` signature)

- [ ] **Step 1: Write failing tests** — `zonesUseProfileAge` (body born 1986-01-01, now 2026-10-21 → age 40 → zones use `maxHeartRate(40)`; compare with `FitnessMath.zones(todayHeart, FitnessMath.maxHeartRate(40))`), `kcalEstimateUsesProfileWeight` (no recorded kcal, 5 000 steps, body 100 kg, no health weight → `FitnessMath.estimatedActiveKcal(5000, 100.0)`), `bodyMetricsCombineProfileAndHealth` (body 74/182 updatedAt T, raw weight 76 at T+1d, fat 14 → Вес 76.0, Рост 182, ИМТ 22.9, Жир 14.0).
- [ ] **Step 2:** `./gradlew :core:jvmTest --tests "*HealthSnapshotBuilderTest"` → FAIL.
- [ ] **Step 3:** Implement; update `FakeHealth` and `HealthConnectProvider`.
- [ ] **Step 4:** `./gradlew :core:allTests :androidApp:testDebugUnitTest :androidApp:assembleDebug -q` → no output.
- [ ] **Step 5:** Commit `feat: health snapshot uses the player's age and weight`.

### Task 4: Android UI — step, about-only mode, profile row

**Files:**
- Create: `androidApp/src/main/java/com/pulse/android/ui/onboarding/AboutYouStep.kt` (`AboutYouStep(form, errors, onChange)` fields composable, `AboutYouSheet` for the profile row, `AboutYouFormSaver`)
- Modify: `ui/onboarding/OnboardingFlow.kt` (`enum class OnboardingMode { FULL, TEST_ONLY, ABOUT_ONLY }` replaces `testOnly: Boolean`; `Step` gets `ABOUT` between `PLAYER` and `TEST_INTRO`; counters «Шаг N из 4»; goals step loses the sleep-hours slider; welcome «Позже» → `dismissOnboarding()` then `ABOUT` when `core.needsAboutYou`, else close; ABOUT «Дальше»/«Сохранить» calls `core.applyAboutYou`, then `refreshHealth()` and `ReminderScheduler.rescheduleAll`; ABOUT_ONLY shows «Выйти из аккаунта» → `core.session.logout()`, no close, back disabled), `ui/main/MainScreen.kt` (trigger also on `core.needsAboutYou`; mode ABOUT_ONLY when survey not needed), `ui/profile/ProfileScreen.kt` (row «О тебе» opens `AboutYouSheet`), `PlayerPortrait` callers of `OnboardingFlow(testOnly = true)` → `OnboardingMode.TEST_ONLY`
- Test: `androidApp/src/test/java/com/pulse/android/ui/onboarding/AboutYouStepTest.kt`; add `aboutOnly` to `AccessibilitySweepTest` and `TextFitSweepTest` (render `OnboardingFlow(OnboardingMode.ABOUT_ONLY)` for a real player core)

Controls: birth date — Material3 `DatePicker` in a dialog limited to the age range; height/weight — `PulseTextField` with number keyboard, error under the field after it loses focus or after «Дальше» is pressed; bedtime/wake — the existing sleep-window time inputs; goals — existing `PulseSlider`s with the ranges/steps from Global Constraints.

- [ ] **Step 1: Write failing tests** (Robolectric, real-player `testCore` with a non-demo account — add `testCore(role, demo = false)` that registers a local account) — `aboutStepRequiresAllFields` («Сохранить» disabled; fill date (via `AboutYouForm` seed hook or picker), «182», «74» → enabled), `aboutStepSurvivesRecreation` (`StateRestorationTester`: typed «182»/«74» remain), `savingAppliesToCore` (after «Сохранить»: `core.body.profile` heightCm 182, `core.needsAboutYou == false`), `aboutOnlyOffersSignOut` (node «Выйти из аккаунта» clickable; clicking → `session.user == null`), sweep tests `aboutOnly` in both sweeps.
- [ ] **Step 2:** `./gradlew :androidApp:testDebugUnitTest --tests "*AboutYouStepTest" --tests "*SweepTest"` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** `./gradlew :androidApp:testDebugUnitTest :androidApp:lintDebug -q` → no output.
- [ ] **Step 5:** Emulator: new account → full flow shows «Шаг 3 из 4 · О тебе»; existing player (survey done, no profile) → about-only on launch, back does nothing, saving closes it and «Фитнес → Тело» shows Вес/Рост/ИМТ, «Сон → Окно сна» shows the entered times; Профиль → «О тебе» edits height. Screenshots at 1.0 and 360dp/1.3.
- [ ] **Step 6:** Commit `feat(android): onboarding step «О тебе»`.

### Task 5: iOS model, math and store (Swift twin of Tasks 1–2)

**Files:**
- Create: `Pulse/Models/BodyProfile.swift` (`BodyProfile: Codable, Equatable` with `birthDate: String` "yyyy-MM-dd" + computed `birthDay: DateComponents`, `heightCm: Int`, `weightKg: Double`, `updatedAt: Date`, `static let demo`; `enum BodyMath`; `struct AboutYouForm`; `enum AboutYouField`; `enum AboutYou` — same members, ranges, rounding and copy as Task 1), `Pulse/Stores/BodyProfileStore.swift` (`@Observable final class BodyProfileStore`, file `body-profile.json`, `reload(user:)`, `save(_:)`)
- Modify: `Pulse/App/AppDependencies.swift` (`let body = BodyProfileStore()`, reload in `activate`, `var needsAboutYou: Bool`, `func applyAboutYou(_ form: AboutYouForm, now: Date = .now) -> Bool`, set `fitness.bodyProfile`), `Pulse/Stores/ActivityStores.swift` (`FitnessStore.bodyProfile`, RPE age, `bodyMetrics` fallback)
- Test: `PulseTests/AboutYouTests.swift` (Swift Testing: the same cases as Task 1 and Task 2's store/apply cases)

- [ ] **Step 1:** Write the failing tests (same names and values as Tasks 1–2, Swift spelling), including `profileJsonShape` with the exact JSON from Global Constraints.
- [ ] **Step 2:** `xcodebuild test -project Pulse.xcodeproj -scheme Pulse -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -only-testing:PulseTests/AboutYouTests -quiet` → build FAIL (missing types).
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same → PASS; full `-only-testing:PulseTests` → PASS.
- [ ] **Step 5:** Commit `feat(ios): body profile and «О тебе» rules`.

### Task 6: iOS health snapshot uses the profile

**Files:**
- Modify: `Pulse/Stores/HealthKitService.swift` (`snapshot(now:body:)`; age = `body?.birthDay ?? store.dateOfBirthComponents()`; latest weight returns value and sample end date; kcal estimate and `bodyMetrics` via `BodyMath`), `HealthDataProviding` protocol and its test fakes, `FitnessStore.refreshHealth` passes `bodyProfile`
- Test: `PulseTests/HealthTests.swift`

- [ ] **Step 1: Failing tests** — `snapshotFakeReceivesProfile` (fake provider records the `body` it was given after `refreshHealth`), `bodyMetricsPreferNewerHealthWeight` (through `BodyMath.metrics` with HealthKit-style inputs). HealthKit queries themselves stay untested (no simulator data), as today.
- [ ] **Step 2:** `xcodebuild test … -only-testing:PulseTests/HealthTests -quiet` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Full `-only-testing:PulseTests` → PASS.
- [ ] **Step 5:** Commit `feat(ios): health snapshot uses the player's age and weight`.

### Task 7: iOS UI — step, about-only mode, profile row

**Files:**
- Create: `Pulse/Features/Onboarding/AboutYouViews.swift` (`AboutYouFields`, `AboutYouSheet`)
- Modify: `Pulse/Features/Onboarding/OnboardingFlowView.swift` (`enum Mode { full, testOnly, aboutOnly }` replaces `testOnly: Bool`; step `.about` between `.player` and `.testIntro`; «Шаг N из 4»; remove the sleep-hours slider and its save code; welcome «Позже» → about when `deps.needsAboutYou`; save → `applyAboutYou`, `fitness.refreshHealth()`, `NotificationScheduler` reschedule as the sleep window editor does; aboutOnly: no close, `interactiveDismissDisabled`, «Выйти из аккаунта» → `session.logout()`), `Pulse/App/MainTabView.swift` (present on `needsAboutYou` too, mode aboutOnly when the survey is not needed), `Pulse/Features/Profile/ProfileView.swift` (row «О тебе» → `AboutYouSheet`), `PlayerPortraitViews.swift` (`OnboardingFlowView(mode: .testOnly)`)

Controls: `DatePicker(.date)` limited to the age range, `PulseTextField` with `.decimalPad`/`.numberPad`, time pickers as in the sleep-window editor, existing `PulseSlider`s; accessibility labels = field labels.

- [ ] **Step 1:** Build: `xcodebuild build -project Pulse.xcodeproj -scheme Pulse -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -quiet` → succeeds after the change; full `-only-testing:PulseTests` → PASS.
- [ ] **Step 2:** Simulator (iPhone 17 Pro, then iPhone 17e): new account → «Шаг 3 из 4 · О тебе»; «Дальше» disabled until all valid; existing player without profile → about-only cover that cannot be swiped away, «Выйти из аккаунта» works; after saving: Фитнес → «Тело» shows Вес/Рост/ИМТ, Сон → окно сна matches, Профиль → Цели match; Профиль → «О тебе» edits height. Screenshots of each.
- [ ] **Step 3:** Commit `feat(ios): onboarding step «О тебе»`.

### Task 8: Parity check and docs

- [ ] **Step 1:** Run both suites: `./gradlew :core:allTests :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug -q` and the full iOS `xcodebuild test … -only-testing:PulseTests`.
- [ ] **Step 2:** Parity: copy strings in `AboutYouStep.kt`/`OnboardingFlow.kt` vs `AboutYouViews.swift`/`OnboardingFlowView.swift` are identical (grep each Global Constraints string on both sides).
- [ ] **Step 3:** `docs/ANDROID.md` — note under row 3.5 that the questionnaire has 4 steps incl. «О тебе» (both platforms).
- [ ] **Step 4:** Commit `docs: questionnaire step «О тебе»`; push `android`.
