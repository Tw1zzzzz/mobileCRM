# Audit fixes (sleep night, registration, «О тебе») Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the usability-audit findings 3–5 on Android and iOS: add a forgotten night by hand, make the registration form readable and keyboard-friendly, and make «О тебе» show its errors and format the birth date.

**Architecture:** Rules live in the shared Kotlin core (`core/…/model`, `core/…/store`) with a Swift twin in `Pulse/Models` + `Pulse/Stores`, as for «О тебе». UI is Compose (`androidApp`) and SwiftUI (`Pulse`). Finding 2 (food photo) needs no change: `canAnalyzePhotos` is already false for real accounts on both platforms.

**Tech Stack:** Kotlin Multiplatform core (kotlin.test), Compose + Robolectric UI tests, SwiftUI + Swift Testing.

**Spec:** usability audit of 2026-10-25 (conversation); the user approved findings 2–5 for both platforms.

## Global Constraints
- Copy in Russian, exactly as written in this plan.
- Same rules and numbers on both platforms (Kotlin and Swift tests mirror each other by name).
- Manual night: wake day is «Сегодня», «Вчера» or «Позавчера»; duration 1–16 h; no overlap with a saved session or a running one; wake time not in the future.
- Error copy: «Подъём ещё не наступил», «Сон — от 1 до 16 часов», «Эта ночь пересекается с уже записанной».
- Registration: a filled field keeps its name visible; «Далее» on the keyboard moves to the next field, the last field closes the keyboard (login: «Войти»); consents sit above «Создать аккаунт».
- «О тебе»: a tap on «Дальше»/«Сохранить» with a wrong answer closes the keyboard and scrolls to the top of the step; Android birth date shows «ДД.ММ.ГГГГ» dots while digits are typed.
- Test passwords in UI tests are literals only in test code (`test-pass-1`), never real ones.

## Review Focus
1. A night whose bedtime is after midnight (01:00 → 09:00) belongs to the wake day, not the day before — test `manualNightAfterMidnight`.
2. A night entered for «Сегодня» before this morning's wake time has passed is rejected, not saved in the future — test `manualNightInFutureIsRejected`.
3. Deleting digits in the masked birth date moves the cursor correctly past the dots — test `birthDateMaskOffsets`.
4. The «Ввод»/«Далее» chain skips hidden fields (team code only for team players) — test `registrationFieldOrder`.
5. Adding a night while a sleep session is running is rejected as an overlap — test `manualNightOverlapsRunningSession`.

---

### Task 1: Core — manual night (Kotlin)

**Files:**
- Modify: `core/src/commonMain/kotlin/com/pulse/core/model/Sleep.kt`, `core/src/commonMain/kotlin/com/pulse/core/store/SleepStore.kt`
- Test: `core/src/commonTest/kotlin/com/pulse/core/store/StoreTests.kt` (class `ManualNightTests`)

**Interfaces:**
- Produces: `data class ManualNight(val wakeDaysAgo: Int, val bedHour: Int, val bedMinute: Int, val wakeHour: Int, val wakeMinute: Int)` with `fun interval(now: Instant): Pair<Instant, Instant>` and `companion fun suggested(window: SleepWindow, now: Instant): ManualNight`; `ManualNight.DAYS = listOf("Сегодня", "Вчера", "Позавчера")`; `SleepStore.addNight(night: ManualNight, now: Instant = Clock.System.now()): String?` (null = saved, else the error copy); `SleepStore.nightError(night, now): String?`.

- [ ] Step 1: tests `manualNightBeforeMidnight` (23:30 → 07:15, today: start = yesterday 23:30, end = today 07:15, saved first in `sessions`), `manualNightAfterMidnight` (01:00 → 09:00 same wake day), `manualNightInFutureIsRejected`, `manualNightTooLongOrShort` (bed 07:00 → wake 07:30 = 30 min and 18 h), `manualNightOverlapsSavedSession`, `manualNightOverlapsRunningSession`, `suggestedNightUsesWindow` (window 23:00/07:00; at 06:00 → wakeDaysAgo 1, at 10:00 → 0).
- [ ] Step 2: run `./gradlew :core:jvmTest -q` — fails to compile (no `ManualNight`).
- [ ] Step 3: implement; bedtime at or after wake time (in minutes of day) means the day before the wake day.
- [ ] Step 4: `./gradlew :core:jvmTest -q` passes.
- [ ] Step 5: commit `feat(core): add a past night by hand`.

### Task 2: Android — «Добавить ночь» sheet

**Files:** Modify `androidApp/src/main/java/com/pulse/android/ui/health/SleepScreens.kt`; Test `androidApp/src/test/java/com/pulse/android/ui/health/AddNightTest.kt`.

**Interfaces:** Consumes Task 1.

- [ ] Step 1: Robolectric test `addNightSavesSession`: registered core, open sleep screen, click «Добавить ночь вручную», click «Сохранить» → `core.sleep.sessions.size == 1`; `addNightShowsError`: running session, save → «Эта ночь пересекается с уже записанной» shown.
- [ ] Step 2: run, fails (no button).
- [ ] Step 3: `SecondaryButton("Добавить ночь вручную")` under «Начать сон» when not sleeping → `AddNightSheet`: chips from `ManualNight.DAYS` under «Проснулся», `TimeInput` «Отбой»/«Подъём» (as `SleepWindowSheet`), duration line «Сон: 8 ч 15 мин», error text, «Сохранить». Empty history copy: «Начни первую сессию сна или добавь прошлую ночь вручную.»; `NoSleepDataCard`: «Нажми «Начать сон», когда ложишься, добавь прошлую ночь вручную или подключи … ».
- [ ] Step 4: AddNightTest + full `:androidApp:testDebugUnitTest` (sweeps) pass.
- [ ] Step 5: commit `feat(android): add a past night by hand`.

### Task 3: Android — registration form

**Files:** Modify `androidApp/src/main/java/com/pulse/android/ui/auth/AuthScreen.kt`; Test `androidApp/src/test/java/com/pulse/android/ui/auth/RegistrationFormTest.kt`; core `core/src/commonMain/kotlin/com/pulse/core/model/` (where `RegistrationForm` lives) for `registrationFields`.

**Interfaces:** Produces `enum class AuthFieldId { NAME, EMAIL, PASSWORD, CONFIRM, FACEIT, TEAM_CODE, TEAM_NAME }` and `fun RegistrationForm.fields(): List<AuthFieldId>` (visible order) in core.

- [ ] Step 1: core test `registrationFieldOrder` (solo: 5 fields; team player adds TEAM_CODE last; staff adds TEAM_NAME last). UI tests: `filledFieldKeepsItsName` (type into «Пароль» → node with text «Пароль» still exists), `consentsAboveCreateButton` (top of «Я принимаю условия» < top of «Создать аккаунт»), `fieldsMoveToNext` (Name field has `ImeAction.Next`, last field `ImeAction.Done`).
- [ ] Step 2: run, fail.
- [ ] Step 3: `AuthField` uses Material `label` instead of `placeholder`, `ImeAction` from position in `fields()`, `KeyboardActions(onNext = moveFocus(Down), onDone = clearFocus / login)`; consents moved above the button.
- [ ] Step 4: tests pass.
- [ ] Step 5: commit `fix(android): readable registration form with keyboard Next`.

### Task 4: Android — «О тебе» errors and birth-date mask

**Files:** core `model/BodyProfile.kt` (`BirthDateMask`); Android `ui/onboarding/AboutYouStep.kt`, `ui/onboarding/OnboardingFlow.kt`; Tests `AboutYouTest.kt`, `AboutYouStepTest.kt`.

**Interfaces:** Produces `object BirthDateMask { fun format(digits: String): String; fun toTransformed(offset: Int, length: Int): Int; fun toOriginal(offset: Int, length: Int): Int }`; `AboutYouDraft.birthText` holds digits only; `AboutYouDraft.submit(onChange, onReveal: () -> Unit, save)`.

- [ ] Step 1: tests `birthDateMaskFormats` («1»→«1», «120»→«12.0», «12032004»→«12.03.2004»), `birthDateMaskOffsets` (length 8: toTransformed 2→2, 3→4, 5→7, 8→10; toOriginal 3→2, 6→4, 10→8); UI `birthDateShowsDots` (type «12032004» → node «12.03.2004»), `tapWithErrorsClosesKeyboard` (weight «250» focused, tap «Сохранить» → field not focused).
- [ ] Step 2: run, fail.
- [ ] Step 3: `VisualTransformation` via `BirthDateMask`; digits only, max 8; on reveal `focusManager.clearFocus()` and scroll the step to 0.
- [ ] Step 4: tests pass.
- [ ] Step 5: commit `fix(android): «О тебе» shows errors over the keyboard, masked birth date`.

### Task 5: iOS — manual night

**Files:** `Pulse/Models/Sleep.swift`, `Pulse/Stores/SleepStore.swift`, `Pulse/Features/Sleep/SleepTrackingViews.swift`, `Pulse/Features/Sleep/SleepView.swift`; Test `PulseTests/ManualNightTests.swift`.

**Interfaces:** Swift twin of Task 1: `struct ManualNight { wakeDaysAgo, bedHour, bedMinute, wakeHour, wakeMinute; func interval(now:) -> (start: Date, end: Date); static func suggested(window:now:); static let days }`, `SleepStore.addNight(_:now:) -> String?`.

- [ ] Step 1: the seven Task 1 tests with the same names.
- [ ] Step 2: run `xcodebuild … test -only-testing:PulseTests/ManualNightTests`, fails.
- [ ] Step 3: implement + «Добавить ночь вручную» button and `AddNightSheet` (segmented `ManualNight.days`, two `DatePicker(.hourAndMinute)`, duration line, error, «Сохранить»), same copy changes as Task 2.
- [ ] Step 4: tests pass.
- [ ] Step 5: commit `feat(ios): add a past night by hand`.

### Task 6: iOS — registration form and «О тебе» reveal

**Files:** `Pulse/Features/Auth/AuthComponents.swift`, `Pulse/Features/Auth/AuthFlowView.swift`, `Pulse/Models/` (registration form file) for `fields`, `Pulse/Features/Onboarding/OnboardingFlowView.swift`, `AboutYouViews.swift`; Test `PulseTests/` registration test file.

**Interfaces:** `enum AuthFieldID` + `RegistrationForm.fields: [AuthFieldID]` (same order as Kotlin).

- [ ] Step 1: test `registrationFieldOrder`.
- [ ] Step 2: run, fail.
- [ ] Step 3: `AuthField` shows its name above typed text, `.submitLabel(.next/.done)` + `@FocusState` chain; consents above the button; «Дальше»/«Сохранить» with errors resign the keyboard and scroll to the step top.
- [ ] Step 4: tests pass; screenshots in the simulator of registration and «О тебе».
- [ ] Step 5: commit `fix(ios): readable registration form, «О тебе» errors over the keyboard`.

### Task 7: Verify and ship
- [ ] pulse-verifier: all suites green.
- [ ] Release APK rebuilt and checked per `releasing-pulse-builds`; emulator walk-through of registration, «О тебе», «Добавить ночь».
- [ ] Push `android`.
