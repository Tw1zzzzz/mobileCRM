import { Header } from "@/components/staff/Header";
import { ProfileView } from "@/components/staff/profile/ProfileView";
import { defaultDeps } from "@/lib/deps";
import { requireStaffContext } from "@/lib/staff/current";
import { loadProfile } from "@/lib/staff/profile";

export const metadata = { title: "Профиль — кабинет стаффа" };

/** The signed-in staff member's own account: nickname, Faceit link, e-mail, password, sessions, deletion. */
export default async function ProfilePage() {
  const ctx = await requireStaffContext();
  const profile = await loadProfile(defaultDeps(), ctx);
  return (
    <>
      <Header title="Профиль" />
      <ProfileView profile={profile} />
    </>
  );
}
