# Staff profile and «Игровая статистика» Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Staff can manage their own account on the website (`/staff/profile`), and enter team games (pracks and tournaments) on the website with a period summary (`/staff/game-stats`) that also feeds the player card and «Статистика».

**Architecture:** Both live in the existing Next.js `server/` staff panel: Server Components for pages, Server Actions for changes (Next's Origin check covers CSRF), libraries in `server/lib/staff/**` with vitest tests against local Postgres. Games are new tables scoped by `team_id`; derived numbers (K/D, KPR, DPR, win rates, rounds, pistols) are pure functions in one module used by the page, the card and «Статистика». The game form is a client component (paste from spreadsheets, live totals, local draft) that posts one JSON payload to a Server Action that re-validates everything. The Android app is not touched.

**Tech Stack:** Next.js 16 App Router, TypeScript, postgres.js, zod, vitest, CSS Modules, hand-written SVG.

**Spec:** `docs/superpowers/specs/2026-10-28-staff-panel-design.md` (tab «Игровая статистика», owner's answers: «Кто играл» — roster player or a stand-in by nickname; rows stay in team history under the nickname after a player leaves; games only on the site). Design board: `docs/superpowers/specs/2026-10-28-staff-panel-design-boards/GameStats.dc.html`. Owner, 2026-11-04: add a profile page to the site; do it in one run including the deploy.

## Global Constraints

- Design source for the games page: `GameStats.dc.html` — layout, copy and thresholds verbatim; demo data replaced by real data or empty states. The profile page follows the panel's existing look (cards, inputs, buttons of the other `/staff` pages).
- Access: every read/write goes through the staff session (`staffSession`) and the caller's `team_id`; another team's game id → not found. Only staff of the team create, edit, delete games.
- Game input limits: opponent ≤ 60 chars; 1–5 maps per game; map ∈ mirage, inferno, nuke, ancient, anubis, dust2, train (+ `overpass`, `vertigo` accepted for games); score 0–30 each side; pistols: two values each «won» / «lost»; 1–10 player rows per map; K, D, A 0–99; ADR 0–300 (one decimal); optional FK, FD 0–30, multikills avg 0–10 (one decimal), clutches 0–20, flash time avg 0–60 s (one decimal). A player row is either a roster player id (teamPlayer of the team) or a stand-in nickname 1–40 chars.
- Derived (one module, tested): K/D = K / max(D,1) (two decimals); KPR = K / rounds; DPR = D / rounds; opening ± = FK − FD; rounds of a map = our + their score; map result: win if our > their, loss if less, draw if equal; game Win Rate = won maps / maps; Round Win Rate = rounds won / rounds; Pistol Win Rate = pistols won / pistols; AVG rounds won/lost per map.
- Profile: nickname 1–40; Faceit link as the app (`faceit.com`/`*.faceit.com`); new password follows the app rule (≥ 8 chars, a letter and a digit) and requires the current password; changing the password revokes all other sessions (keeps this browser signed in); email change sends a 6-digit code to the NEW address (purpose `email_change`, 15 min, 5 attempts) and applies only after the code; «Выйти на всех устройствах» revokes every refresh token of the user including the web session; «Удалить аккаунт» requires the password and uses the existing account deletion (team handover rules unchanged).
- Copy (verbatim) — profile: title «Профиль»; sections «Основное» (fields «Ник», «Ссылка на Faceit», button «Сохранить»), «Почта» (current address, field «Новая почта», button «Получить код», then «Код из письма», button «Подтвердить»), «Пароль» (fields «Текущий пароль», «Новый пароль», «Повторите пароль», button «Сменить пароль»), «Сеансы» (button «Выйти на всех устройствах»), «Удаление аккаунта» (text «Аккаунт и все данные будут удалены без возможности восстановления.», field «Пароль», button «Удалить аккаунт»); notices «Сохранено», «Почта изменена», «Пароль изменён»; errors reuse the app's texts («Неверный логин или пароль», «Неверный код», «Срок действия кода истёк. Запросите новый», «Этот email уже зарегистрирован», password rule text of the app).
- No invented numbers; «нет данных» where a value cannot be computed (e.g. no rounds).

## Review Focus

- A staff member of team A posts a game form with team B's game id or team B's player id → refused, nothing written. Test `gamesAreTeamScoped` (Task 2).
- A pasted spreadsheet block with extra columns, blank lines, commas as decimal separators, or more rows than players → the table fills what it can, reports «не заполнено» cells, never crashes. Test `pasteParsesMessyRows` (Task 3).
- A player removed from the team → their rows stay in old games under their nickname; the card of a current player is unaffected. Test `removedPlayerRowsKeepNickname` (Task 2).
- Email change to an address that is already registered → «Этот email уже зарегистрирован», no code sent. Test `emailChangeToTakenAddressRefused` (Task 1).
- Password change from the profile → other devices' sessions stop working, this browser stays signed in. Test `passwordChangeRevokesOtherSessions` (Task 1).

---

### Task 1: Profile page `/staff/profile`

**Files:**
- Create: `server/app/staff/(panel)/profile/page.tsx`, `server/components/staff/profile/*.tsx`, `server/components/staff/profile/profile.module.css`, `server/lib/staff/profile.ts`, `server/test/staff-profile.test.ts`
- Modify: `server/lib/auth/codes.ts` (purpose `email_change`, code bound to the new address), `server/db/migrations/013_email_change.sql` (allow the new purpose; store the pending new address), `server/app/staff/actions.ts` (profile actions), `server/lib/staff/nav.ts` / header (avatar or link «Профиль»)

**Interfaces:**
- Produces: `updateProfile(deps, ctx, {nickname, faceitUrl})`, `changePassword(deps, ctx, current, next, keepRefreshHash)`, `requestEmailChange(deps, ctx, newEmail)`, `confirmEmailChange(deps, ctx, code)`, `signOutEverywhere(deps, ctx)`, `deleteOwnAccount(deps, ctx, password)` — each throws the existing `ApiError` codes; actions map them to the copy.

- [ ] **Step 1: Write failing tests:** `profileUpdatesNickAndFaceit`, `invalidFaceitRefused`, `passwordChangeNeedsCurrent`, `passwordChangeRevokesOtherSessions`, `emailChangeSendsCodeToNewAddress`, `emailChangeToTakenAddressRefused`, `emailChangeWrongCodeCountsAttempts`, `signOutEverywhereRevokesAll`, `deleteNeedsPassword`, `deleteRemovesAccount`.
- [ ] **Step 2:** `cd server && npm test -- staff-profile` → FAIL.
- [ ] **Step 3: Implement** lib, migration, page and actions.
- [ ] **Step 4:** `npm test`, `npx tsc --noEmit`, `npx next build` → PASS.
- [ ] **Step 5: Commit** `feat(server): staff profile page`.

### Task 2: Games storage and calculations

**Files:**
- Create: `server/db/migrations/014_team_games.sql`, `server/lib/staff/games.ts` (CRUD + validation), `server/lib/staff/games-math.ts` (derived numbers), `server/test/games.test.ts`, `server/test/games-math.test.ts`

**Interfaces:**
- Tables: `team_games(id uuid pk, team_id → teams on delete cascade, kind 'pracc'|'tournament', played_on date, opponent text null, created_by → users on delete set null, created_at, updated_at)`, `team_game_maps(id, game_id → team_games on delete cascade, position int, map text, our_score int, their_score int, pistol1 'won'|'lost', pistol2 'won'|'lost')`, `team_game_rows(id, map_id → team_game_maps on delete cascade, player_id uuid null → users on delete set null, nickname text not null, kills, deaths, assists, adr numeric, fk, fd, multikills numeric, clutches, flash_time numeric — optional ones null)`. `nickname` is stored at write time (kept after the player leaves).
- `saveGame(deps, ctx, input: GameInput): Promise<string>` (create or replace a game by id in one transaction), `deleteGame(deps, ctx, id)`, `listGames(deps, ctx, filter: {from, to, kind?, playerId?}): Promise<GameSummary[]>`, `getGame(deps, ctx, id)`; `games-math.ts` exports the formulas of Global Constraints (`mapResult`, `kd`, `kpr`, `dpr`, `openingDiff`, `gameTotals`, `periodTotals`).

- [ ] **Step 1: Write failing tests:** validation limits (each bound), `gamesAreTeamScoped`, `onlyStaffWrite`, `standInByNickname`, `removedPlayerRowsKeepNickname`, `replaceGameIsAtomic`, `deleteGame`, and math cases (draw, zero deaths, zero rounds → null, pistols, period totals across games).
- [ ] **Step 2:** `npm test -- games` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `npm test`, `npx tsc --noEmit` → PASS.
- [ ] **Step 5: Commit** `feat(server): team games storage and calculations`.

### Task 3: «Внести игру» form

**Files:**
- Create: `server/app/staff/(panel)/game-stats/page.tsx`, `server/components/staff/game-stats/GameForm.tsx` (client), `server/components/staff/game-stats/paste.ts` (spreadsheet paste parser), `server/components/staff/game-stats/game-stats.module.css`, `server/test/game-paste.test.ts`, `server/test/staff-game-stats.test.tsx`
- Modify: `server/app/staff/actions.ts` (`saveGameAction`, `deleteGameAction`), `server/lib/staff/nav.ts` («Игровая статистика» becomes a link)

**Interfaces:**
- Consumes: Task 2.
- Form per the board: step 1 «Игра» (Тип: Пракк / Турнир, Дата, Соперник «— по желанию», «Кто играл»: «Команда · N» or one player), step 2 «Карты и счёт» (add/remove map, Карта, Счёт «Мы : Соперник», both pistols, result shown), step 3 «Статистика игроков · {карта}» table (row per player of «Кто играл», stand-in row addable), computed columns K/D, KPR, DPR, «Опен ±» shown live from `games-math.ts`, «Итог игры · считается сам» block, «Перед сохранением» checklist exactly like the board, «Сохранить игру», «Черновик сохраняется автоматически» (localStorage per team, cleared on save), «Очистить».
- Paste: Ctrl+V of TSV/CSV/semicolon blocks into any cell fills from that cell right/down; decimal comma accepted; non-numbers leave the cell empty.

- [ ] **Step 1: Write failing tests:** `pasteParsesMessyRows`, `pasteFromMiddleCell`, `decimalComma`; render tests `formShowsRosterRows`, `checklistLists missing required cells`, `noDemoNames`.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `npm test`, `npx tsc --noEmit`, `npx next build` → PASS.
- [ ] **Step 5: Commit** `feat(server): game entry form`.

### Task 4: «Сводка за период» and the game list

**Files:**
- Modify: `server/app/staff/(panel)/game-stats/page.tsx`, `server/components/staff/game-stats/*`
- Test: `server/test/staff-game-stats.test.tsx` (extend)

**Interfaces:**
- Per the board: periods «7 дней», «30 дней», «90 дней», «Свой период»; filters «Все / Пракки / Турниры» and player; tiles «Игр за период», «K/D», «ADR», «Win Rate по картам»; the per-game columns newest first with the board's rows; «Нет игр для этого фильтра» / «Выберите другой источник или игрока». Each game opens for editing in the form (Task 3) and can be deleted (confirm «Удалить игру? Она пропадёт из статистики команды»). Filters live in the URL.

- [ ] **Step 1: Write failing tests:** `summaryTotalsFromGames`, `filterByKindAndPlayer`, `customPeriod`, `emptyFilterMessage`, `editOpensForm`, `deleteGame`.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `npm test`, `npx tsc --noEmit`, `npx next build` → PASS.
- [ ] **Step 5: Commit** `feat(server): game stats period summary`.

### Task 5: Games in the player card and «Статистика»

**Files:**
- Modify: `server/lib/staff/card.ts` (`GAME_SOURCES` = all, faceit, pracc, tournament), `server/lib/staff/card-game.ts`, `server/lib/staff/statistics*.ts`, related tests

**Interfaces:**
- Card «Источник игр»: «Все / Пракки / Faceit / Турниры» as on the board; site games of that player (rows with their `player_id`) give K/D, ADR, win rate and match lists the same way Faceit does; «Все» merges both.
- «Статистика»: game metrics per day include site games of the selected players in addition to Faceit.

- [ ] **Step 1: Write failing tests:** `cardShowsPraccsForPlayer`, `cardAllMergesSources`, `statisticsIncludesSiteGames`.
- [ ] **Step 2:** run → FAIL. **Step 3:** implement. **Step 4:** `npm test`, `npx tsc --noEmit`, `npx next build` → PASS.
- [ ] **Step 5: Commit** `feat(server): site games in the card and statistics`.

### Task 6: Deploy and check

- [ ] **Step 1:** push; production deployment via the Vercel MCP (`create_deployment`, `target: "production"`); `READY`; `/v1/health` ok; `/staff/profile` and `/staff/game-stats` answer (redirect to login when signed out).
- [ ] **Step 2:** locally (`npm run seed:local && npm run dev`) in the browser pane: enter a game with two maps by paste, see the summary and the card source filter; change nickname and password in the profile.
- [ ] **Step 3:** `docs/STAFF_PANEL.md` — what is live; commit `docs: staff profile and game stats`.
