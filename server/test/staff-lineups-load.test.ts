import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { loadStaffLineups, type LineupMap, type StaffLineupType } from "../lib/staff/lineups";
import { resetDb, testDeps } from "./helpers";
import { seedCardTeam, type CardTeam } from "./staff-card-fixture";
import { addMember } from "./team-fixture";

const deps = testDeps();

beforeEach(async () => {
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

async function addLineup(
  team: CardTeam,
  title: string,
  map: LineupMap,
  type: StaffLineupType,
  options: { side?: "t" | "ct"; details?: string; teamId?: string; favoriteOf?: string } = {},
): Promise<string> {
  const [row] = await deps.sql<{ id: string }[]>`
    insert into staff_lineups (team_id, created_by, title, map, side, type, details, created_at, updated_at)
    values (${options.teamId ?? team.ids.team}, ${team.ids.staff}, ${title}, ${map}, ${options.side ?? "t"}, ${type}, ${options.details ?? ""},
      ${deps.now()}, ${deps.now()})
    returning id`;
  if (options.favoriteOf) {
    await deps.sql`insert into staff_lineup_favorites (lineup_id, staff_user_id) values (${row.id}, ${options.favoriteOf})`;
  }
  deps.advance(1_000);
  return row.id;
}

async function seed() {
  const team = await seedCardTeam(deps);
  const coach2 = await addMember(deps, "coach2", "staff", team.ids.team);
  const smoke = await addLineup(team, "Смок в окно", "mirage", "smoke", { favoriteOf: team.ids.staff });
  await addLineup(team, "Флешка на B", "mirage", "flash", { side: "ct", details: "Из апартаментов" });
  await addLineup(team, "Дефолт B", "inferno", "script", { favoriteOf: coach2 });
  await addLineup(team, "Молотов", "inferno", "molotov", { favoriteOf: team.ids.staff });
  await addLineup(team, "Заметка", "nuke", "note");
  // another team's entry must never be counted or listed
  await addLineup(team, "Чужой смок", "mirage", "smoke", { teamId: (await deps.sql<{ id: string }[]>`select team_id as id from users where id = ${team.ids.otherStaff}`)[0].id });
  return { team, coach2, smoke };
}

describe("loadStaffLineups: counters", () => {
  it("countsTheTeamsBaseAndThisStaffMembersFavourites", async () => {
    const { team } = await seed();
    const page = await loadStaffLineups(deps, team.ctx, {});
    expect(page.stats).toEqual({ total: 5, lineups: 3, scripts: 1, favorites: 2 });
    expect(page.totalForTeam).toBe(5);
    expect(page.entries.map((e) => e.title)).not.toContain("Чужой смок");
  });

  it("favouritesAreOfTheSignedInStaffMemberOnly", async () => {
    const { coach2, team } = await seed();
    const ctx = { ...team.ctx, userId: coach2 };
    const page = await loadStaffLineups(deps, ctx, {});
    expect(page.stats.favorites).toBe(1);
    expect(page.entries.filter((e) => e.favorite).map((e) => e.title)).toEqual(["Дефолт B"]);
  });

  it("countersIgnoreTheFilters", async () => {
    const { team } = await seed();
    const page = await loadStaffLineups(deps, team.ctx, { map: "nuke", type: "note" });
    expect(page.stats.total).toBe(5);
    expect(page.entries).toHaveLength(1);
  });
});

describe("loadStaffLineups: map chips", () => {
  it("mapCountsFollowEveryFilterButTheMap", async () => {
    const { team } = await seed();
    const all = await loadStaffLineups(deps, team.ctx, {});
    expect(all.mapCounts).toEqual({ all: 5, mirage: 2, inferno: 2, nuke: 1, ancient: 0, anubis: 0, dust2: 0, train: 0 });

    const ct = await loadStaffLineups(deps, team.ctx, { side: "ct", map: "inferno" });
    // the list is the Inferno entries on the CT side (none), the chips still count every map on that side
    expect(ct.entries).toHaveLength(0);
    expect(ct.mapCounts.all).toBe(1);
    expect(ct.mapCounts.mirage).toBe(1);

    const favourites = await loadStaffLineups(deps, team.ctx, { fav: "1" });
    expect(favourites.mapCounts).toMatchObject({ all: 2, mirage: 1, inferno: 1 });
  });

  it("searchLooksAtTitleMapAndDescription", async () => {
    const { team } = await seed();
    const byDescription = await loadStaffLineups(deps, team.ctx, { q: "апартамент" });
    expect(byDescription.entries.map((e) => e.title)).toEqual(["Флешка на B"]);
    const byMap = await loadStaffLineups(deps, team.ctx, { q: "inferno" });
    expect(byMap.entries).toHaveLength(2);
    expect(byMap.mapCounts.all).toBe(2);
  });
});

describe("loadStaffLineups: selection and dialog", () => {
  it("opensTheFirstEntryOrTheOneAsked", async () => {
    const { team, smoke } = await seed();
    const first = await loadStaffLineups(deps, team.ctx, {});
    expect(first.selected?.title).toBe("Заметка"); // newest first
    const asked = await loadStaffLineups(deps, team.ctx, { entry: smoke });
    expect(asked.selected?.id).toBe(smoke);
  });

  it("theDialogIsAskedByTheUrl", async () => {
    const { team } = await seed();
    expect((await loadStaffLineups(deps, team.ctx, { form: "script" })).form).toBe("script");
    expect((await loadStaffLineups(deps, team.ctx, { form: "lineup" })).form).toBe("lineup");
    expect((await loadStaffLineups(deps, team.ctx, { form: "edit" })).form).toBe("edit");
    expect((await loadStaffLineups(deps, team.ctx, { form: "<script>" })).form).toBeNull();
  });

  it("editNeedsAnEntryToEdit", async () => {
    const team = await seedCardTeam(deps);
    const page = await loadStaffLineups(deps, team.ctx, { form: "edit" });
    expect(page.selected).toBeNull();
    expect(page.form).toBeNull();
    expect(page.stats).toEqual({ total: 0, lineups: 0, scripts: 0, favorites: 0 });
  });

  it("todayIsTheStaffCalendarDay", async () => {
    const { team } = await seed();
    expect((await loadStaffLineups(deps, team.ctx, {})).today).toBe("2026-10-26");
  });
});
