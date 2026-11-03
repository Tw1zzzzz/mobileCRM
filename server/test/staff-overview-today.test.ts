import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { loadTodayEvents } from "../lib/staff/overview-today";
import { resetDb, testDeps } from "./helpers";
import { seedCardTeam, type CardTeam } from "./staff-card-fixture";
import { addMember } from "./team-fixture";

const deps = testDeps();
const TZ = "Europe/Moscow";

beforeEach(async () => {
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

async function addEvent(
  team: CardTeam,
  teamId: string,
  title: string,
  startsAt: string,
  options: { tag?: "team" | "personal"; createdBy?: string } = {},
): Promise<void> {
  const start = new Date(startsAt);
  await deps.sql`
    insert into staff_calendar_events (team_id, created_by, title, tag, starts_at, ends_at)
    values (${teamId}, ${options.createdBy ?? team.ids.staff}, ${title}, ${options.tag ?? "team"}, ${start}, ${new Date(start.getTime() + 3_600_000)})`;
}

/** The fixed test clock is 2026-10-26 12:00 UTC = 15:00 in Moscow: «today» is the 26th. */
describe("loadTodayEvents", () => {
  it("listsTodaysEventsInOrderWithTheirTimeInTheStaffZone", async () => {
    const team = await seedCardTeam(deps);
    await addEvent(team, team.ids.team, "Матч", "2026-10-26T16:00:00Z"); // 19:00 Moscow
    await addEvent(team, team.ids.team, "Тренировка", "2026-10-26T09:00:00Z"); // 12:00 Moscow
    const events = await loadTodayEvents(deps, team.ctx, TZ);
    expect(events.map((e) => [e.title, e.time])).toEqual([["Тренировка", "12:00"], ["Матч", "19:00"]]);
  });

  it("theDayIsTheStaffDayNotUtc", async () => {
    const team = await seedCardTeam(deps);
    await addEvent(team, team.ids.team, "Ночной скрим", "2026-10-25T21:30:00Z"); // 00:30 on the 26th in Moscow
    await addEvent(team, team.ids.team, "Поздний разбор", "2026-10-26T20:59:00Z"); // 23:59 on the 26th
    await addEvent(team, team.ids.team, "Завтрашний", "2026-10-26T21:00:00Z"); // 00:00 on the 27th
    await addEvent(team, team.ids.team, "Вчерашний", "2026-10-25T20:59:00Z"); // 23:59 on the 25th
    const events = await loadTodayEvents(deps, team.ctx, TZ);
    expect(events.map((e) => e.title)).toEqual(["Ночной скрим", "Поздний разбор"]);
  });

  it("showsOnlyThisTeamsEventsAndOnlyMyPersonalOnes", async () => {
    const team = await seedCardTeam(deps);
    const coach2 = await addMember(deps, "coach2", "staff", team.ids.team);
    const otherTeamId = (await deps.sql<{ id: string }[]>`select team_id as id from users where id = ${team.ids.otherStaff}`)[0].id;
    await addEvent(team, team.ids.team, "Общее", "2026-10-26T09:00:00Z");
    await addEvent(team, team.ids.team, "Моё личное", "2026-10-26T10:00:00Z", { tag: "personal" });
    await addEvent(team, team.ids.team, "Личное коллеги", "2026-10-26T11:00:00Z", { tag: "personal", createdBy: coach2 });
    await addEvent(team, otherTeamId, "Чужая команда", "2026-10-26T12:00:00Z", { createdBy: team.ids.otherStaff });
    const events = await loadTodayEvents(deps, team.ctx, TZ);
    expect(events.map((e) => e.title)).toEqual(["Общее", "Моё личное"]);
  });

  it("noEventsGivesAnEmptyList", async () => {
    const team = await seedCardTeam(deps);
    expect(await loadTodayEvents(deps, team.ctx, TZ)).toEqual([]);
  });
});
