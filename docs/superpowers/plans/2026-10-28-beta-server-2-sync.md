# Beta server 2/5 — data and photo sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every real (non-demo) account's collections and meal photos live on the server: a player who reinstalls the app or signs in on another phone gets all their records back, and nothing is lost when two phones edit the same collection.

**Architecture:** The server stores each collection as one JSON document per user with an integer `version` (optimistic concurrency) and meal photos as JPEG rows, both in Postgres (spec «Переносимость»). The phone uploads changed collections with the `base_version` it last saw; on `409 version_conflict` it downloads the newer copy, merges it with the local one **per record** (three-way, using the record ids it had at the last sync as the base), and uploads the result. Photos are cut out of `nutrition.json` before upload and sent separately. Android drives sync with WorkManager.

**Tech Stack:** as plan 1 — Next.js App Router + TypeScript, postgres.js, zod, vitest against local Postgres; Kotlin 2.4 + Ktor 3.6 (`MockEngine`), kotlinx.serialization `JsonElement`, WorkManager 2.12, Robolectric.

**Spec:** `docs/superpowers/specs/2026-10-26-beta-server-design.md`, section «Синхронизация данных — документы коллекций» (stage 2 of «Этапы»). The staff panel spec `docs/superpowers/specs/2026-10-28-staff-panel-design.md` builds on this plan; nothing here is staff-specific.

## Global Constraints

- Plan 1's Global Constraints hold (envelope, error mapping, `requireUser`, snake_case wire format, demo stays local, iOS untouched, Russian copy, immutable Kotlin models).
- Collections (name = file name without `.json`), exactly: `sleep`, `nutrition`, `wellbeing`, `cognitive`, `calendar`, `fitness-state`, `screen-time`, `lineups`, `game-goals`, `faceit`, `player-survey`, `body-profile`. Any other name → `422 validation_failed`. `session.json`, `accounts.json`, `sync-ledger.json`, `sync-photos.json` are never sent.
- Documents are stored **as the phone wrote them** (camelCase keys inside, `PulseJson` encoding); the server never interprets their contents except `nutrition` meal ids (photo cleanup).
- New error codes → HTTP: `not_found` 404, `version_conflict` 409, `payload_too_large` 413.
- Limits: document body ≤ 4 000 000 bytes; photo ≤ 1 000 000 bytes and must start with JPEG magic `FF D8 FF`; sync and photo endpoints 120 requests/min per user (`rateLimit(deps, "user:" + userId, 120, 60_000)`).
- Only accounts with server tokens sync. Demo accounts and the `guest` folder never sync (no request is made).
- Offline the app works exactly as before; pending changes leave when the network returns. A sync failure never blocks the UI and never deletes local records.
- Copy (verbatim): loading after sign-in «Загружаем ваши данные…»; if that first download fails: «Не удалось загрузить данные с сервера. Покажем то, что есть на телефоне, и попробуем ещё раз позже.» with button «Понятно».

## Review Focus

- A record added on phone A while offline and a different record added on phone B → after both sync, **both** phones have both records. Test `concurrentAddsOnTwoPhonesKeepBoth` (Task 4).
- A record deleted on phone A, then phone B (which still has it) syncs → it does **not** come back on A. Test `remoteDeletionIsNotResurrected` (Task 3) and `localDeletionSurvivesConflict` (Task 3).
- Fresh install, sign in with a month of data including meal photos → all records and photos appear; a photo that fails to download leaves the meal without a photo, not missing. Test `pullAllRestoresMealsAndPhotos` (Task 4), `missingPhotoKeepsMeal` (Task 4).
- Sign out with unsynced changes, sign in again on the same phone → changes are still there and get uploaded. Test `pendingChangesSurviveLogoutLogin` (Task 5).
- A corrupt or foreign-shaped document downloaded from the server (e.g. written by a newer app version with extra keys) → the app keeps working; unknown keys are preserved through a merge, not dropped. Test `mergeKeepsUnknownKeys` (Task 3).

---

### Task 1: Server — collection documents

**Files:**
- Create: `server/db/migrations/004_sync.sql`, `server/lib/sync.ts`, `server/app/v1/sync/route.ts`, `server/app/v1/sync/[collection]/route.ts`, `server/test/sync.test.ts`
- Modify: `server/lib/http.ts` (three error codes), `server/test/helpers.ts` (`resetDb` truncates the new tables)

**Interfaces:**
- Consumes: `requireUser(deps, req): Promise<string>`, `rateLimit`, `ok`, `parseBody`, `ApiError`, `handle`, `defaultDeps` (plan 1).
- Produces: table `sync_documents(user_id uuid references users on delete cascade, collection text, version int not null, document jsonb not null, updated_at timestamptz not null, primary key (user_id, collection))`; `COLLECTIONS: readonly string[]` exported from `server/lib/sync.ts`; handlers `listSync(req, deps)`, `getSyncDocument(req, deps, collection)`, `putSyncDocument(req, deps, collection)`.
- Wire:
  - `GET /v1/sync` → `[{ collection, version, updated_at }]` (only collections that exist).
  - `GET /v1/sync/{collection}` → `{ collection, version, updated_at, document }`; none stored → `404 not_found`.
  - `PUT /v1/sync/{collection}` body `{ base_version: int | null, document: <any JSON> }` → `{ version, updated_at }`. `base_version` must equal the stored version (`null` = nothing stored yet); otherwise `409 version_conflict` with `error.message` null and `meta: { current_version }`. First write → version 1; each accepted write → +1.

- [ ] **Step 1: Write failing tests** in `server/test/sync.test.ts`:
  - `putThenGetReturnsSameDocument` — PUT `sleep` with `base_version: null` → 200 `{version: 1}`; GET → `document` deep-equals the sent one, `version` 1.
  - `staleBaseVersionConflicts` — two PUTs with `base_version: null` → second is 409 `version_conflict`, `meta.current_version` 1, stored document unchanged.
  - `versionIncrementsOnEachWrite` — PUT null → 1, PUT 1 → 2, GET `/sync` lists `{collection: "sleep", version: 2}`.
  - `unknownCollectionRejected` — PUT `/sync/session` → 422 `validation_failed`.
  - `documentsArePerUser` — user A's `sleep` is not visible to user B (B's GET → 404).
  - `oversizedDocumentRejected` — body over 4 000 000 bytes → 413 `payload_too_large`, nothing stored.
  - `requiresAuth` — no token → 401 `unauthorized`.
  - `deleteMeRemovesDocuments` — after `DELETE /me`, no `sync_documents` rows for that user.
- [ ] **Step 2:** `cd server && npm test -- sync` → FAIL (module missing).
- [ ] **Step 3: Implement** migration, handlers and routes. The version check and write are one statement (`insert … on conflict (user_id, collection) do update … where sync_documents.version = $base` and check the affected row), so two concurrent PUTs with the same base cannot both win. Check the size from `content-length` and the actual body length before parsing.
- [ ] **Step 4:** `npm test` and `npx tsc --noEmit` → all PASS.
- [ ] **Step 5: Commit** `feat(server): per-user collection documents with versioned writes`.

### Task 2: Server — meal photos

**Files:**
- Create: `server/db/migrations/005_meal_photos.sql`, `server/lib/photos.ts`, `server/app/v1/photos/[mealId]/route.ts`, `server/test/photos.test.ts`
- Modify: `server/lib/sync.ts` (cleanup on `nutrition` write), `server/test/helpers.ts`

**Interfaces:**
- Consumes: Task 1 (`putSyncDocument`, `COLLECTIONS`).
- Produces: table `meal_photos(user_id uuid references users on delete cascade, meal_id uuid, jpeg bytea not null, updated_at timestamptz not null, primary key (user_id, meal_id))`; handlers `putPhoto(req, deps, mealId)`, `getPhoto(req, deps, mealId)`.
- Wire: `PUT /v1/photos/{meal_id}` body = raw JPEG (`content-type: image/jpeg`) → `{}` (upsert). `GET /v1/photos/{meal_id}` → `200` raw JPEG with `content-type: image/jpeg` (not the JSON envelope); none → `404 not_found` (envelope). `meal_id` not a UUID → 422 `validation_failed`.

- [ ] **Step 1: Write failing tests** in `server/test/photos.test.ts`:
  - `putThenGetReturnsSameBytes` — bytes and `content-type` round-trip.
  - `nonJpegRejected` — body starting `89 50 4E 47` (PNG) → 422 `validation_failed`.
  - `oversizedPhotoRejected` — 1 000 001 bytes → 413 `payload_too_large`.
  - `photosArePerUser` — B's GET of A's meal id → 404.
  - `nutritionWriteDropsOrphanPhotos` — photos for meals `m1`, `m2`; PUT `nutrition` whose `meals` contains only `m1` → `m2`'s photo is gone, `m1`'s stays.
  - `deleteMeRemovesPhotos`.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement.** Orphan cleanup runs in the same transaction as the accepted `nutrition` write: delete photos whose `meal_id` is not in `document.meals[*].id` (missing or non-array `meals` → keep all photos).
- [ ] **Step 4:** `npm test`, `npx tsc --noEmit` → PASS.
- [ ] **Step 5: Commit** `feat(server): meal photos stored apart from the nutrition document`.

### Task 3: Core — per-record three-way merge

**Files:**
- Create: `core/src/commonMain/kotlin/com/pulse/core/sync/CollectionMerge.kt`, `core/src/commonTest/kotlin/com/pulse/core/sync/CollectionMergeTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  /** Where the records of a collection document live. `""` = the document itself is the list. */
  data class MergeSpec(val lists: Map<String, String>, val maps: Set<String> = emptySet())

  object CollectionMerge {
      val specs: Map<String, MergeSpec>
      /** Record keys per list field, used as the merge base after a successful sync. */
      fun recordKeys(collection: String, document: JsonElement): Map<String, Set<String>>
      fun merge(collection: String, local: JsonElement, remote: JsonElement,
                base: Map<String, Set<String>>, localIsNewer: Boolean): JsonElement
  }
  ```
- `specs` (field → key of each record), exactly:
  `sleep` {sessions→id}; `nutrition` {meals→id}, maps {water}; `wellbeing` {mood→id, balance→id}; `cognitive` {results→id, gameResults→id}; `calendar` {""→id}; `fitness-state` {workouts→id}, maps {ratings}; `screen-time` {entries→date}; `lineups` {""→id}; `game-goals` {}; `faceit` {snapshots→date}; `player-survey` {mindset→id}; `body-profile` {}.

Rules for `merge` (per list field; key = the record's key value as a string):
- key in local and remote → local record.
- only local → keep if key ∉ base (new here), drop if key ∈ base (deleted on the other phone).
- only remote → keep if key ∉ base (new there), drop if key ∈ base (deleted here).
- Order: local order, then remote-only kept records in remote order.
- Map fields: union; same key → local value.
- Every other top-level field (scalars, nested objects, unknown keys): taken whole from local if `localIsNewer`, else from remote. Unknown keys of kept records are untouched.
- A document that is not the expected shape (e.g. a list field that is not an array) → that field follows the scalar rule; `merge` never throws.

- [ ] **Step 1: Write failing tests** in `CollectionMergeTest`:
  - `bothSidesAddDifferentRecords` — sleep local {a,b}, remote {a,c}, base {a} → {a,b,c}.
  - `remoteDeletionIsNotResurrected` — local {a,b}, remote {a}, base {a,b} → {a}.
  - `localDeletionSurvivesConflict` — local {a}, remote {a,b}, base {a,b} → {a}.
  - `sameRecordLocalWins` — local a(kcal 500), remote a(kcal 300) → kcal 500.
  - `rootListCollections` — `calendar` and `lineups` (document is an array) merge the same way.
  - `keyedByDate` — `screen-time` entries and `faceit` snapshots merge by `date`.
  - `mapsUnionLocalWins` — nutrition `water` {"2026-10-27":500} + remote {"2026-10-27":300,"2026-10-28":200} → {"2026-10-27":500,"2026-10-28":200}.
  - `scalarsFollowNewerSide` — `game-goals` whole document: `localIsNewer=false` → remote; `true` → local; `sleep.window` likewise.
  - `mergeKeepsUnknownKeys` — a remote-only record with an extra key `"futureField"` keeps it; a top-level unknown key on the newer side survives.
  - `wrongShapeDoesNotThrow` — remote `sessions` is a string → result is a valid object, no exception.
  - `recordKeysListsEveryField` — `recordKeys("cognitive", doc)` → `{results: {...}, gameResults: {...}}`.
- [ ] **Step 2:** `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :core:allTests --tests '*CollectionMergeTest*'` → FAIL.
- [ ] **Step 3: Implement** on `JsonElement`/`JsonObject`/`JsonArray` only (no model classes), so every collection uses one code path.
- [ ] **Step 4:** same command → PASS.
- [ ] **Step 5: Commit** `feat(core): per-record three-way merge of collection documents`.

### Task 4: Core — sync engine (push, pull, conflicts, photos)

**Files:**
- Create: `core/src/commonMain/kotlin/com/pulse/core/sync/SyncEngine.kt`, `core/src/commonMain/kotlin/com/pulse/core/sync/MealPhotos.kt`, `core/src/commonTest/kotlin/com/pulse/core/sync/SyncEngineTest.kt`
- Modify: `core/src/commonMain/kotlin/com/pulse/core/api/Api.kt` (remove the old `SyncEngine`; add `ApiClient.sendBytes`/`getBytes` for JPEG; map `version_conflict` → a typed `ApiException` with `currentVersion`), `core/src/commonMain/kotlin/com/pulse/core/storage/Storage.kt` (`SyncLedger.Entry` gains `version: Int? = null`, `baseKeys: Map<String, Set<String>>? = null`, `remoteUpdatedAt: Instant? = null`; old ledger files still decode). Uploaded photo ids are kept in `sync-photos.json` in the account folder, written directly through `scope.files` (never marked changed, never sent).

**Interfaces:**
- Consumes: `CollectionMerge` (Task 3), `ApiClient`, `DataScope`, `SyncLedger`.
- Produces:
  ```kotlin
  class SyncEngine(client: ApiClient, scope: DataScope, ledger: SyncLedger = SyncLedger(scope)) {
      val isEnabled: Boolean
      /** Uploads every pending collection; on version_conflict pulls, merges, re-uploads (max 3 rounds). */
      suspend fun pushPending(folder: String = scope.folder()): SyncResult
      /** GET /sync, then downloads every collection newer than the ledger's version and merges it in. */
      suspend fun pullChanged(folder: String = scope.folder()): SyncResult
  }
  data class SyncResult(val uploaded: List<String>, val downloaded: List<String>, val failed: Boolean)
  object MealPhotos {
      fun strip(nutrition: JsonElement): JsonElement           // photo → absent, adds "photoRef": true
      fun photos(nutrition: JsonElement): Map<String, String>  // meal id → base64 JPEG
      fun restore(nutrition: JsonElement, photos: Map<String, String>): JsonElement
  }
  ```
- Behaviour:
  - Push of `nutrition`: first upload each local photo not in `uploadedPhotos` (`PUT /photos/{id}`), then PUT the stripped document. The local file keeps its photos.
  - Pull/merge of `nutrition`: merge stripped documents, then restore local photos; for meals with `photoRef` and no local photo, `GET /photos/{id}`; a failed or 404 photo leaves the meal without a photo.
  - After a successful upload or download of a collection: ledger records `version`, `baseKeys = CollectionMerge.recordKeys(...)` of the document now on both sides, `syncedAt`, `remoteUpdatedAt`.
  - `localIsNewer` for the merge = the ledger's `changedAt` > the server's `updated_at`.
  - A merge result different from the local file is written to the local file **without** `markChanged`-ing a loop: the engine uploads it in the same round.
  - Any `ApiException` other than `version_conflict` stops the round with `failed = true`; already-uploaded collections stay marked.

- [ ] **Step 1: Write failing tests** in `SyncEngineTest` (MockEngine server that keeps documents and versions in memory, `MemoryFileSystem`):
  - `pushSendsBaseVersionAndStoresNewVersion`.
  - `conflictPullsMergesAndRepushes` — server has version 2 with record c; local base version 1 with new record b → final PUT carries {a,b,c} with `base_version` 2.
  - `concurrentAddsOnTwoPhonesKeepBoth` — two engines over two file systems, one server: each adds a record, both push and pull → both files contain both records.
  - `pullAllRestoresMealsAndPhotos` — empty phone, server has nutrition with `photoRef` meals and photos → local `nutrition.json` has the base64 photos.
  - `missingPhotoKeepsMeal` — photo GET 404 → meal present, `photo` absent.
  - `photosUploadedOnceAndStrippedFromDocument` — the PUT body has no `photo` keys; a second push uploads no photo again.
  - `offlineLeavesChangesPending` — network error → `failed = true`, ledger still pending, local file unchanged.
  - `demoFolderNeverSyncs` — folder `accounts/demo-teamPlayer` → no request.
  - `oldLedgerFileStillLoads` — a `sync-ledger.json` in the plan-1 format decodes and is treated as never synced.
- [ ] **Step 2:** run `:core:allTests` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `./gradlew :core:allTests` → all PASS (plan-1 tests included).
- [ ] **Step 5: Commit** `feat(core): sync engine with conflict merge and meal photos`.

### Task 5: Core — when sync runs

**Files:**
- Modify: `core/src/commonMain/kotlin/com/pulse/core/PulseCore.kt`, `core/src/commonMain/kotlin/com/pulse/core/storage/Storage.kt` (change listener), `core/src/commonMain/kotlin/com/pulse/core/auth/SessionStore.kt` (logout pushes first)
- Test: `core/src/commonTest/kotlin/com/pulse/core/sync/SyncTriggersTest.kt`

**Interfaces:**
- Consumes: `SyncEngine` (Task 4).
- Produces on `PulseCore`:
  ```kotlin
  sealed interface SyncStatus { data object Idle; data object FirstLoad; data object FirstLoadFailed; data object Running }
  val syncStatus: StateFlow<SyncStatus>
  val isSyncable: Boolean                      // remote build, signed in, not demo
  var onLocalChange: (() -> Unit)?             // platform debounces and schedules a push
  suspend fun syncNow(): SyncResult            // pullChanged, reload stores if anything was downloaded, then pushPending
  fun acknowledgeFirstLoadFailure()            // FirstLoadFailed → Idle
  ```
- Behaviour:
  - After a successful sign-in, email verification or password reset of a real account: status `FirstLoad`, `syncNow()`; on success stores reload from the merged files and status → `Idle`; on failure → `FirstLoadFailed` (local data shown).
  - Every `JsonFileStorage.save` of a syncable account calls `onLocalChange`.
  - `SessionStore.logout()` for a syncable account first tries `pushPending` with a 5 s timeout, then signs out regardless. The account folder stays on the phone.

- [ ] **Step 1: Write failing tests:** `signInTriggersFirstLoadAndReloadsStores` (server has a sleep session → after sign-in `core.sleep` shows it), `firstLoadOfflineFailsSoftly` (status `FirstLoadFailed`, stores show local data), `saveNotifiesLocalChange`, `demoSaveDoesNotNotify`, `pendingChangesSurviveLogoutLogin` (offline logout, then sign in online → the change reaches the server), `logoutDoesNotWaitForeverOffline` (logout completes within the timeout).
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `./gradlew :core:allTests` → PASS.
- [ ] **Step 5: Commit** `feat(core): sync after sign-in, on change and before logout`.

### Task 6: Android — background sync and first-load screen

**Files:**
- Create: `androidApp/src/main/java/com/pulse/android/platform/SyncScheduler.kt` (`SyncWorker : CoroutineWorker` + scheduling), `androidApp/src/test/java/com/pulse/android/SyncSchedulerTest.kt`
- Modify: `androidApp/src/main/java/com/pulse/android/PulseApplication.kt` (wire `onLocalChange`, app-start and background triggers), the root composable that hosts the signed-in UI (show the first-load state), `androidApp/build.gradle.kts` only if `androidx.lifecycle:lifecycle-process` or `work-testing` is missing

**Interfaces:**
- Consumes: `PulseCore.syncStatus`, `syncNow()`, `onLocalChange`, `acknowledgeFirstLoadFailure()` (Task 5).
- Produces: `SyncScheduler.schedule(context, delay: Duration)` — unique work `pulse-sync` (`ExistingWorkPolicy.REPLACE`), `NetworkType.CONNECTED`, exponential backoff starting 30 s.
- Triggers: local change → `schedule(10.seconds)` (edits are batched); app goes to background (`ProcessLifecycleOwner` ON_STOP) → `schedule(0)`; app start with a syncable account → `schedule(0)`.
- UI: `FirstLoad` → full-screen loader with «Загружаем ваши данные…»; `FirstLoadFailed` → dialog with the failure copy and «Понятно» (calls `acknowledgeFirstLoadFailure()`). Nothing else changes in the UI.

- [ ] **Step 1: Write failing tests** (Robolectric + `WorkManagerTestInitHelper`): `localChangeEnqueuesDelayedSync`, `backgroundEnqueuesImmediateSync`, `workerRunsSyncNow` (fake core), `firstLoadShowsLoader`, `firstLoadFailureShowsDialogAndDismisses`.
- [ ] **Step 2:** `./gradlew :androidApp:testDebugUnitTest` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `./gradlew :androidApp:testDebugUnitTest :androidApp:lintDebug` → PASS.
- [ ] **Step 5: Commit** `feat(android): background sync and first-load screen`.

### Task 7: Deploy and check end to end

**Files:**
- Modify: `docs/API.md` (sync section: the new PUT body, versions, conflicts, photos, error codes, limits), `docs/ANDROID.md` (one paragraph: how sync behaves and how to reset it)

- [ ] **Step 1:** With the owner's OK: push the branch and create a **production** deployment of the pushed commit through the Vercel MCP (`create_deployment`, `target: "production"`): the project's production branch is not this branch, so a push alone only builds a preview. Check the deployment is `READY` and `GET /v1/health` is `{"ok":true,"db":true}`.
- [ ] **Step 2:** Build a debug APK against `https://pulse-gold-nine.vercel.app/v1/` and install on the `PulsePixel` emulator. Walk: sign in (owner's test account) → add a sleep night, a meal **with a photo**, a mood entry, a calendar event → wait for sync (or background the app) → clear the app's data (`adb shell pm clear com.pulsecrm.mvp`) → sign in again → «Загружаем ваши данные…» → all four records are back, the meal has its photo.
- [ ] **Step 3:** Offline check: airplane mode on the emulator → add a record → app works, nothing lost → network on → record reaches the server (`GET /v1/sync` version increased, checked with the account's token from a test sign-in, never printed).
- [ ] **Step 4: Commit** docs — `docs: sync endpoints, conflicts and photos`.

---

## Self-review notes

- Spec «Синхронизация»: collections list (Global Constraints), unknown rejected (Task 1), photos apart and stored in Postgres (Task 2), `base_version` (Task 1), merge by records with deletions not coming back (Task 3), sync after sign-in / after changes with debounce / on background / at start / when network returns (Tasks 5–6, WorkManager network constraint), offline unchanged (Tasks 4–6), same mechanism on a phone that already has data (Task 5 `pendingChangesSurviveLogoutLogin`), account deletion removes everything (Tasks 1–2).
- Deletions are tracked with the per-collection `baseKeys` in the local ledger instead of tombstones in the document: the server stays a dumb document store, and the spec allows either («список удалённых id в документе или в журнале синхронизации»).
- Staff reading players' documents is plan «Кабинет A», not here.
