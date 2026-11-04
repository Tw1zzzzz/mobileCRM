import { Header } from "@/components/staff/Header";
import { SignOutButton } from "@/components/staff/SignOutButton";
import { TeamManagement } from "@/components/staff/players/TeamManagement";
import { ProfileView } from "@/components/staff/profile/ProfileView";
import { defaultDeps } from "@/lib/deps";
import { requireStaffContext } from "@/lib/staff/current";
import type { SearchParams } from "@/lib/staff/overview";
import { loadTeamPage } from "@/lib/staff/players";
import { isLastStaff, loadProfile } from "@/lib/staff/profile";

export const metadata = { title: "Профиль — кабинет стаффа" };

/** The signed-in staff member's own account: nickname, Faceit link, e-mail, password, sessions, team, deletion. */
export default async function ProfilePage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const ctx = await requireStaffContext();
  const deps = defaultDeps();
  const [profile, lastStaff, teamPage] = await Promise.all([
    loadProfile(deps, ctx),
    isLastStaff(deps, ctx),
    loadTeamPage(deps, ctx, await searchParams),
  ]);
  return (
    <>
      <Header title="Профиль" trailing={<SignOutButton />} />
      <ProfileView
        profile={profile}
        teamName={ctx.teamName}
        lastStaff={lastStaff}
        team={<TeamManagement team={teamPage.team} confirm={teamPage.confirm} error={teamPage.error} />}
      />
    </>
  );
}
