/**
 * Local preview data for the staff website: a verified staff account with a team, three players with two weeks of
 * synced documents, and a team knowledge base of lineups and scripts.
 *
 *   npm run seed:local && npm run dev
 *
 * Runs against `DATABASE_URL` from `server/.env.local` only, and refuses anything but localhost / 127.0.0.1 / ::1.
 * Safe to run again: the previous seed team and its accounts are replaced.
 *
 *   Test login (local only):  staff@pulse.local  /  Pulse-bbPrP1dheJqd
 */
import postgres from "postgres";
import { migrate } from "../db/migrate";
import { createSql } from "../db/sql";
import { hashPassword } from "../lib/auth/passwords";

export const SEED_EMAIL = "staff@pulse.local";
export const SEED_PASSWORD = "Pulse-bbPrP1dheJqd";
const SEED_TEAM = "Atlant Esports";
const SEED_INVITE = "SEED22";
const SEED_STAFF_CODE = "SEED33";
const DAYS = 14;
const MOSCOW = "+03:00";
const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "::1", "[::1]"]);

/** The host of a database URL, lower case; null when the URL cannot be read. */
export function hostOf(url: string): string | null {
  try {
    return new URL(url).hostname.toLowerCase();
  } catch {
    return null;
  }
}

export function isLocalDatabase(url: string | undefined): url is string {
  if (!url) return false;
  const host = hostOf(url);
  return host !== null && LOCAL_HOSTS.has(host);
}

/** Small deterministic generator: the same seed gives the same two weeks, so screenshots are comparable. */
function rng(seed: number): () => number {
  let s = seed % 2147483647;
  if (s <= 0) s += 2147483646;
  return () => {
    s = (s * 16807) % 2147483647;
    return (s - 1) / 2147483646;
  };
}

const clamp = (v: number, a: number, b: number) => Math.max(a, Math.min(b, v));
const pad = (n: number) => String(n).padStart(2, "0");

/** `YYYY-MM-DD` of `daysAgo` days before today in Moscow. */
function moscowDay(now: Date, daysAgo: number): string {
  const shifted = new Date(now.getTime() - daysAgo * 86_400_000);
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Moscow", year: "numeric", month: "2-digit", day: "2-digit" }).format(shifted);
}

function previousDay(day: string): string {
  return new Date(Date.parse(`${day}T00:00:00Z`) - 86_400_000).toISOString().slice(0, 10);
}

function at(day: string, hour: number, minute = 0): string {
  return new Date(Date.parse(`${day}T${pad(hour)}:${pad(minute)}:00${MOSCOW}`)).toISOString();
}

interface Profile {
  nickname: string;
  email: string;
  /** Shifts every metric: positive is a fitter player. */
  base: number;
  /** Days (counted back from today) the player did not sync anything. */
  gaps: readonly number[];
}

const PLAYERS: readonly Profile[] = [
  { nickname: "vexa", email: "vexa@pulse.local", base: 0.4, gaps: [] },
  { nickname: "frost", email: "frost@pulse.local", base: -0.6, gaps: [9, 5] },
  { nickname: "Kairo", email: "kairo@pulse.local", base: 0.9, gaps: [12] },
];

const COGNITIVE_KINDS = [
  ["reaction", (s: number) => `${Math.round(420 - s * 2)} мс`],
  ["aim", (s: number) => `${Math.round(s)}%`],
  ["memory", (s: number) => String(Math.round(s / 10) + 3)],
  ["stroop", (s: number) => `${Math.round(s * 0.9)}%`],
  ["goNoGo", (s: number) => `${Math.round(s)}%`],
] as const;

const MEALS = [
  ["Овсянка с ягодами", 8, 30, 0.22],
  ["Курица с рисом", 13, 30, 0.38],
  ["Греческий салат и сёмга", 19, 0, 0.3],
  ["Йогурт и орехи", 16, 30, 0.1],
] as const;

interface PlayerDocs {
  sleep: unknown;
  wellbeing: unknown;
  cognitive: unknown;
  nutrition: unknown;
}

function documentsFor(profile: Profile, index: number, now: Date): PlayerDocs {
  const random = rng(index * 97 + 11);
  const sessions: unknown[] = [];
  const mood: unknown[] = [];
  const results: unknown[] = [];
  const meals: unknown[] = [];
  let z = profile.base * 0.6;
  for (let ago = DAYS - 1; ago >= 0; ago -= 1) {
    z = 0.6 * z + (random() - 0.5) * 1.3 + profile.base * 0.12;
    if (profile.gaps.includes(ago)) continue;
    const day = moscowDay(now, ago);
    const sleepHours = clamp(7.1 + 1.0 * z + (random() - 0.5) * 1.3, 4.5, 9.8);
    const bedtime = new Date(Date.parse(at(day, 7)) - sleepHours * 3_600_000);
    sessions.push({ id: `s-${index}-${ago}`, start: bedtime.toISOString(), end: at(day, 7) });

    const entries = 1 + Math.round(random());
    for (let n = 0; n < entries; n += 1) {
      mood.push({
        id: `m-${index}-${ago}-${n}`,
        day: at(day, 0),
        mood: Math.round(clamp(6.2 + 2.0 * z + (random() - 0.5) * 3.2, 1, 10)),
        energy: Math.round(clamp(5.8 + 1.8 * z + (random() - 0.5) * 3, 1, 10)),
      });
    }

    const score = Math.round(clamp(62 + 9 * z + (random() - 0.5) * 12, 20, 100));
    const [kind, metric] = COGNITIVE_KINDS[(ago + index) % COGNITIVE_KINDS.length];
    results.push({ id: `c-${index}-${ago}`, kind, date: at(day, 11, 20), score, metric: metric(score) });

    const dayKcal = clamp(2100 + z * 180 + (random() - 0.5) * 500, 1500, 3000);
    MEALS.forEach(([name, hour, minute, share], n) => {
      const kcal = Math.round(dayKcal * share);
      meals.push({
        id: `00000000-0000-4000-8000-${pad(index)}${pad(ago)}0000000${n}`.slice(0, 36),
        date: at(day, hour, minute),
        name,
        kcal,
        protein: Math.round(kcal * 0.07),
        fat: Math.round(kcal * 0.03),
        carbs: Math.round(kcal * 0.11),
        source: "manual",
        photoRef: false,
      });
    });
  }
  return { sleep: { sessions }, wellbeing: { mood }, cognitive: { results }, nutrition: { meals } };
}

type LineupType = "smoke" | "flash" | "molotov" | "grenade" | "script" | "note";
interface SeedLineup {
  title: string;
  type: LineupType;
  map: string;
  side: "t" | "ct";
  author: "staff" | number;
  daysAgo: number;
  favorite: boolean;
  video: string | null;
  details: string;
}

const LINEUPS: readonly SeedLineup[] = [
  { title: "Смок в окно с T-спавна", type: "smoke", map: "mirage", side: "t", author: "staff", daysAgo: 2, favorite: true, video: "https://youtu.be/dQw4w9WgXcQ", details: "Встать в угол у ящика на T-спавне\nПрицел на верхний край антенны\nПрыжок с броском (jumpthrow)" },
  { title: "Флешка на выход B-апартаментов", type: "flash", map: "mirage", side: "t", author: 0, daysAgo: 3, favorite: false, video: null, details: "Из апартаментов, у второго окна\nБросок через стену на отскок от потолка\nEntry выходит сразу после хлопка" },
  { title: "Молотов в дарк на банане", type: "molotov", map: "inferno", side: "ct", author: "staff", daysAgo: 4, favorite: true, video: "https://youtu.be/dQw4w9WgXcQ", details: "С CT-спавна по прямой\nБросок с прыжком\nВыжигает дарк до 20 секунды раунда" },
  { title: "Дефолт B-сайт Inferno", type: "script", map: "inferno", side: "t", author: "staff", daysAgo: 5, favorite: false, video: null, details: "Kairo — флешка в банан, первый выход\nvexa — смок CT и молотов в дарк\nfrost — держит мид с AWP, поздний заход" },
  { title: "Смок на outside", type: "smoke", map: "nuke", side: "t", author: 1, daysAgo: 6, favorite: false, video: "https://youtu.be/dQw4w9WgXcQ", details: "Из-за красного контейнера\nПрицел на трубу на крыше\nБросок стоя" },
  { title: "Ретейк A Ancient", type: "script", map: "ancient", side: "ct", author: "staff", daysAgo: 6, favorite: true, video: null, details: "Сбор на CT: смок в донат, флеш от frost\nKairo — заход через храм\nvexa — ротация с B за 15 секунд\nОбщий заход по флешке в 0:40" },
  { title: "Флешка на A-мейн", type: "flash", map: "ancient", side: "t", author: 2, daysAgo: 8, favorite: false, video: null, details: "От T-рампы, левый угол\nЛевая кнопка, без прыжка" },
  { title: "HE в бокс на B", type: "grenade", map: "anubis", side: "t", author: 2, daysAgo: 9, favorite: false, video: null, details: "С канала, у стены\nДобивает игрока в боксе после молотова" },
  { title: "Смок CT-кросс", type: "smoke", map: "dust2", side: "t", author: "staff", daysAgo: 11, favorite: false, video: "https://youtu.be/dQw4w9WgXcQ", details: "Из-за двойных дверей на T-спавне\nПрицел на угол здания\nJumpthrow" },
  { title: "Молотов на ивент", type: "molotov", map: "train", side: "ct", author: 1, daysAgo: 13, favorite: false, video: null, details: "С CT-спавна поверх вагонов\nБросок стоя на правую кнопку" },
  { title: "Тайминги по картам на разбор", type: "note", map: "mirage", side: "t", author: "staff", daysAgo: 1, favorite: false, video: null, details: "Разобрать записи последних двух игр\nСверить тайминги заходов на B" },
];

/** Two team events today, so the header card of «Обзор» has something to show. */
const CALENDAR_TODAY = [
  { title: "Тренировка", hour: 12, details: "Разбор вчерашней игры, затем дефолты на Inferno" },
  { title: "Матч ESL", hour: 19, details: "Онлайн, BO3" },
] as const;

async function ensureDatabase(url: string): Promise<void> {
  const target = new URL(url);
  const name = decodeURIComponent(target.pathname.slice(1));
  if (!/^[a-z0-9_]+$/i.test(name)) throw new Error(`Unsafe database name: ${name}`);
  const admin = new URL(url);
  admin.pathname = "/postgres";
  const sql = postgres(admin.toString(), { max: 1, onnotice: () => {} });
  try {
    const found = await sql`select 1 from pg_database where datname = ${name}`;
    if (found.length === 0) await sql.unsafe(`create database "${name}"`);
  } finally {
    await sql.end();
  }
}

async function seed(url: string): Promise<void> {
  await ensureDatabase(url);
  const sql = createSql(url);
  try {
    await migrate(sql);
    const now = new Date();
    const passwordHash = await hashPassword(SEED_PASSWORD);
    const emails = [SEED_EMAIL, ...PLAYERS.map((p) => p.email)];

    await sql.begin(async (tx) => {
      await tx`update teams set owner_id = null where name = ${SEED_TEAM} or invite_code = ${SEED_INVITE}`;
      await tx`delete from users where email in ${tx(emails)}`;
      await tx`delete from teams where name = ${SEED_TEAM} or invite_code = ${SEED_INVITE}`;

      const [team] = await tx<{ id: string }[]>`
        insert into teams (name, invite_code, staff_code, created_at)
        values (${SEED_TEAM}, ${SEED_INVITE}, ${SEED_STAFF_CODE}, ${now}) returning id`;
      const addUser = async (email: string, nickname: string, role: "staff" | "teamPlayer") => {
        const [row] = await tx<{ id: string }[]>`
          insert into users (email, nickname, password_hash, role, team_id, email_verified_at,
            accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at, created_at, updated_at)
          values (${email}, ${nickname}, ${passwordHash}, ${role}, ${team.id}, ${now},
            ${now}, ${now}, '2026-10-26', ${now}, ${now}, ${now}) returning id`;
        return row.id;
      };
      const staffId = await addUser(SEED_EMAIL, "Coach", "staff");
      await tx`update teams set owner_id = ${staffId} where id = ${team.id}`;
      const playerIds = [];
      for (const player of PLAYERS) playerIds.push(await addUser(player.email, player.nickname, "teamPlayer"));

      for (const [index, player] of PLAYERS.entries()) {
        const docs = documentsFor(player, index, now);
        for (const [collection, document] of Object.entries(docs)) {
          await tx`
            insert into sync_documents (user_id, collection, version, document, updated_at)
            values (${playerIds[index]}, ${collection}, 1, ${tx.json(document as never)}, ${now})`;
        }
      }

      const today = moscowDay(now, 0);
      for (const event of CALENDAR_TODAY) {
        await tx`
          insert into staff_calendar_events (team_id, created_by, title, details, tag, starts_at, ends_at, created_at, updated_at)
          values (${team.id}, ${staffId}, ${event.title}, ${event.details}, 'team', ${at(today, event.hour)}, ${at(today, event.hour + 2)}, ${now}, ${now})`;
      }

      for (const lineup of LINEUPS) {
        const authorId = lineup.author === "staff" ? staffId : playerIds[lineup.author];
        const created = new Date(now.getTime() - lineup.daysAgo * 86_400_000);
        const [row] = await tx<{ id: string }[]>`
          insert into staff_lineups (team_id, created_by, title, map, side, type, details, video_url, created_at, updated_at)
          values (${team.id}, ${authorId}, ${lineup.title}, ${lineup.map}, ${lineup.side}, ${lineup.type}, ${lineup.details},
            ${lineup.video}, ${created}, ${created}) returning id`;
        if (lineup.favorite) await tx`insert into staff_lineup_favorites (lineup_id, staff_user_id) values (${row.id}, ${staffId})`;
      }
    });
    console.log(`Seeded "${SEED_TEAM}" in ${new URL(url).pathname.slice(1)}: 1 staff, ${PLAYERS.length} players (${DAYS} days), ${LINEUPS.length} lineups, ${CALENDAR_TODAY.length} events today.`);
    console.log(`Test login (local only): ${SEED_EMAIL} / ${SEED_PASSWORD}`);
    console.log("Start the site with: npm run dev  ->  http://localhost:3000/staff/login");
  } finally {
    await sql.end();
  }
}

async function main(): Promise<void> {
  const url = process.env.DATABASE_URL;
  if (!url) throw new Error("DATABASE_URL is missing: put it in server/.env.local (npm run seed:local reads that file).");
  if (!isLocalDatabase(url)) {
    throw new Error(`Refusing to seed: the database host "${hostOf(url) ?? "unreadable"}" is not localhost / 127.0.0.1 / ::1.`);
  }
  await seed(url);
}

if (process.argv[1]?.endsWith("seed-local.ts")) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : error);
    process.exit(1);
  });
}
