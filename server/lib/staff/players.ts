import type { Deps } from "@/lib/deps";
import { playerDocuments, teamPlayers, type StaffContext } from "@/lib/staff/access";
import {
  STAFF_TIME_ZONE,
  dailyInputs,
  dayOf,
  readiness,
  readinessStatus,
  shiftDay,
  type ReadinessStatus,
} from "@/lib/staff/metrics";
import { NO_DATA, fmt1, hm, whole } from "@/lib/staff/overview-format";
import { OVERVIEW_COLLECTIONS, type SearchParams } from "@/lib/staff/overview";
import { TEAM_ERRORS, type TeamError } from "@/lib/staff/team-management";
import { teamMembers } from "@/lib/team/members";
import { teamCodes } from "@/lib/teams";

/** «Игроки»: the roster with today's readiness and the team block (codes, members), from real data only. */

/** What a cell shows when the player has no value for it today. */
export const EMPTY_CELL = "—";

const MONTHS = ["янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"] as const;

export interface PlayerRow {
  id: string;
  nickname: string;
  /** 0..100, null without any input today. */
  readiness: number | null;
  status: ReadinessStatus | null;
  sleep: string;
  mood: string;
  cog: string;
  /** «сегодня, 09:17», «вчера, 23:40», «3 окт, 14:05» or «нет данных». */
  lastActivity: string;
}

export interface RosterSummary {
  ready: number;
  doubt: number;
  risk: number;
  noData: number;
}

export interface RosterData {
  rows: PlayerRow[];
  summary: RosterSummary;
}

export interface MemberEntry {
  id: string;
  nickname: string;
}

export interface TeamData {
  playerCode: string;
  staffCode: string;
  /** The signed-in staff member, marked «(вы)» in the list. */
  selfId: string;
  staff: MemberEntry[];
  players: MemberEntry[];
}

/** What the visitor asked to confirm (`?confirm=`); only things that exist in the caller's team. */
export type TeamConfirm =
  | { kind: "rotate"; code: "player" | "staff" }
  | { kind: "remove"; member: MemberEntry };

export interface PlayersPage {
  roster: RosterData;
  team: TeamData;
  confirm: TeamConfirm | null;
  error: TeamError | null;
}

function clock(instant: Date, tz: string): string {
  return new Intl.DateTimeFormat("ru-RU", { timeZone: tz, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(instant);
}

/** The board's «последняя активность», in the staff time zone. */
export function lastActivityLabel(last: Date | null, now: Date, tz: string): string {
  if (last === null) return NO_DATA;
  const day = dayOf(last, tz);
  const today = dayOf(now, tz);
  const time = clock(last, tz);
  if (day === today) return `сегодня, ${time}`;
  const [year, month, date] = day.split("-").map(Number);
  if (day === shiftDay(today, -1)) return `вчера, ${time}`;
  const sameYear = year === Number(today.slice(0, 4));
  return `${date} ${MONTHS[month - 1]}${sameYear ? "" : ` ${year}`}, ${time}`;
}

function summarize(rows: readonly PlayerRow[]): RosterSummary {
  const count = (status: ReadinessStatus | null): number => rows.filter((r) => r.status === status).length;
  return { ready: count("ready"), doubt: count("doubt"), risk: count("risk"), noData: count(null) };
}

async function loadRoster(deps: Deps, ctx: StaffContext, tz: string): Promise<RosterData> {
  const players = await teamPlayers(deps, ctx);
  const today = dayOf(deps.now(), tz);
  const rows = await Promise.all(
    players.map(async (p): Promise<PlayerRow> => {
      const docs = await playerDocuments(deps, ctx, p.id, OVERVIEW_COLLECTIONS);
      const inputs = dailyInputs(docs, today, tz);
      const value = readiness(inputs);
      return {
        id: p.id,
        nickname: p.nickname,
        readiness: value,
        status: value === null ? null : readinessStatus(value),
        sleep: inputs.sleepHours === undefined ? EMPTY_CELL : hm(inputs.sleepHours),
        mood: inputs.mood === undefined ? EMPTY_CELL : fmt1(inputs.mood),
        cog: inputs.cog === undefined ? EMPTY_CELL : whole(inputs.cog),
        lastActivity: lastActivityLabel(p.lastActivity, deps.now(), tz),
      };
    }),
  );
  return { rows, summary: summarize(rows) };
}

function firstOf(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

/** `?confirm=rotate-player|rotate-staff|remove-<member id>`; an id outside the caller's team is ignored. */
function parseConfirm(raw: string | undefined, team: TeamData): TeamConfirm | null {
  if (raw === "rotate-player") return { kind: "rotate", code: "player" };
  if (raw === "rotate-staff") return { kind: "rotate", code: "staff" };
  if (raw === undefined || !raw.startsWith("remove-")) return null;
  const id = raw.slice("remove-".length);
  const member = [...team.staff, ...team.players].find((m) => m.id === id);
  return member ? { kind: "remove", member } : null;
}

function parseError(raw: string | undefined): TeamError | null {
  return TEAM_ERRORS.find((e) => e === raw) ?? null;
}

async function loadTeam(deps: Deps, ctx: StaffContext): Promise<TeamData> {
  const [codes, members] = await Promise.all([teamCodes(deps, ctx.teamId), teamMembers(deps, ctx.teamId)]);
  const entry = (m: { id: string; nickname: string }): MemberEntry => ({ id: m.id, nickname: m.nickname });
  return {
    playerCode: codes.player,
    staffCode: codes.staff,
    selfId: ctx.userId,
    staff: members.filter((m) => m.role === "staff").map(entry),
    players: members.filter((m) => m.role === "teamPlayer").map(entry),
  };
}

/** Everything the page needs. Player data goes through `lib/staff/access.ts` only. */
export async function loadPlayers(
  deps: Deps,
  ctx: StaffContext,
  params: SearchParams,
  tz: string = STAFF_TIME_ZONE,
): Promise<PlayersPage> {
  const [roster, team] = await Promise.all([loadRoster(deps, ctx, tz), loadTeam(deps, ctx)]);
  return {
    roster,
    team,
    confirm: parseConfirm(firstOf(params.confirm), team),
    error: parseError(firstOf(params.error)),
  };
}
