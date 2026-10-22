# Pulse для Android — план и статус

Ветка: `android`. iOS-проект остаётся на своих местах (`Pulse/`, `PulseWidgets/`, `Shared/`), чтобы изменения
из `main` вливались без конфликтов. Рядом появляются:

```
core/         Kotlin Multiplatform: модели, расчёты, хранилище, API — общее для Android (и позже iOS)
androidApp/   Android-приложение на Jetpack Compose
```

Стек: Kotlin, Kotlin Multiplatform, Jetpack Compose, kotlinx.serialization, kotlinx.datetime, Ktor,
Health Connect, WorkManager, Glance. Минимальная версия — Android 9 (API 28).
Модуль ядра называется `core`, а не `shared`: на macOS имена папок без учёта регистра, и `shared` совпал бы
с iOS-папкой `Shared/`.

ID приложения пока `com.pulsecrm.mvp` (как на iOS) — меняется до первой загрузки в Google Play.

Формат данных совпадает с iOS: JSON, даты ISO 8601, те же поля — данные и будущий сервер общие.

## Шаги

| # | Шаг | Статус |
|---|---|---|
| 0 | Инструменты (JDK 17, Gradle), каркас Gradle-проекта; Android SDK — в шаге 2 | ✅ |
| 1.1 | Ядро: даты (неделя с понедельника, ru), форматтеры, статистика, генератор демо-данных | ✅ сверено с iOS бит в бит |
| 1.2 | Ядро: модели и расчёты — сон, нагрузка и восстановление, фитнес, цели, самочувствие, тесты, игра и Faceit, итоги недели, сводка команды, анкета и «Игровой характер» | ✅ |
| 1.3 | Ядро: демо-данные (те же числа, что на iOS) | ✅ сверено с iOS |
| 1.4 | Ядро: хранилище по аккаунтам, локальные аккаунты, API-клиент, синхронизация, сторы всех разделов, корень приложения (`PulseCore`) | ✅ |
| 1.5 | Тесты ядра — паритет с iOS | ✅ 124 теста ядра; тесты навигации, уведомлений и экранов переедут в шаги 2–4 |
| 2 | Каркас Android: дизайн-система, навигация (5 вкладок, «+», двойной тап), вход и демо | ✅ проверено на эмуляторе |
| 3.1 | Экраны: «Сегодня», хаб «Состояние», уведомления | ✅ |
| 3.2 | Экраны: сон, фитнес, питание (фото с камеры/галереи → анализ), экранное время | ✅ проверено на эмуляторе; реальные данные телефона — в шаге 4 |
| 3.3 | Экраны: тесты (когнитивные, настроение, колесо баланса, игровые) | ✅ батарея из 5 тестов пройдена на эмуляторе |
| 3.4 | Экраны: статистика и графики, разделы «Статистика» и «Аналитика» хаба | ✅ расчёт в ядре покрыт тестами |
| 3.5 | Экраны: игра и Faceit, цели, профиль, анкета, итоги недели | ✅ анкета при первом входе проверена на новом аккаунте |
| 3.6 | Экраны: дневник CS2 / база знаний, календарь | ✅ напоминания о событиях — в шаге 4 |
| 3.7 | Экраны стаффа: игроки, карточка, аналитика, сводка команды | ✅ проверено на эмуляторе |
| 4 | Платформа: Health Connect, реальное экранное время, уведомления, трекинг сна, виджет, плитка | ✅ проверено на эмуляторе; lint-ошибки `StateFlow.value` в composition — в шаге 5 |
| 5 | Качество: разные экраны и устройства, фон, разрешения, TalkBack (галочки согласий при регистрации — без роли «флажок») | |
| 6 | Google Play: страница, Data Safety, формы Health Connect и Usage Access, закрытый тест | |

## Сборка

```bash
./gradlew :core:allTests                 # тесты ядра
./gradlew :androidApp:testDebugUnitTest  # тесты Android-части
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:assembleRelease --no-configuration-cache   # release с R8
```

Release собирается только без configuration cache: задача Compose-компилятора `produceReleaseComposeMapping`
не умеет кэшироваться. Для отладочных сборок кэш остаётся включённым.

## Проверка на эмуляторе

```bash
# эмулятор с окном (AVD PulsePixel, Android 16)
$ANDROID_HOME/emulator/emulator -avd PulsePixel
# или без окна
$ANDROID_HOME/emulator/emulator -avd PulsePixel -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
# тестовые данные в Health Connect (только debug-сборка; сначала выдать WRITE_* из src/debug/AndroidManifest.xml через `adb shell pm grant`)
adb shell am broadcast -n com.pulsecrm.mvp/com.pulse.android.debug.SeedHealthReceiver
# открыть экран сразу: --es demo player|solo|staff --es route sleep|fitness|screen|calendar|…
adb shell am start -n com.pulsecrm.mvp/com.pulse.android.MainActivity --es demo player --es route sleep
```
