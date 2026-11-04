import type { Profile } from "@/lib/staff/profile";
import { DeleteForm } from "./DeleteForm";
import { EmailForm } from "./EmailForm";
import { MainForm } from "./MainForm";
import { PasswordForm } from "./PasswordForm";
import { SessionsForm } from "./SessionsForm";
import styles from "./profile.module.css";

/** «Профиль»: the staff member's own account, one card per concern. */
export function ProfileView({ profile, lastStaff }: { profile: Profile; lastStaff: boolean }) {
  return (
    <div className={styles.sections}>
      <MainForm nickname={profile.nickname} faceitUrl={profile.faceitUrl} />
      <EmailForm current={profile.email} pending={profile.pendingEmail} />
      <PasswordForm />
      <SessionsForm />
      <DeleteForm lastStaff={lastStaff} />
    </div>
  );
}
