import type { ReactNode } from "react";
import type { Profile } from "@/lib/staff/profile";
import { DeleteForm } from "./DeleteForm";
import { EmailForm } from "./EmailForm";
import { MainForm } from "./MainForm";
import { PasswordForm } from "./PasswordForm";
import { SessionsForm } from "./SessionsForm";
import styles from "./profile.module.css";

interface ProfileViewProps {
  profile: Profile;
  lastStaff: boolean;
  /** The «Команда» section (codes and members), shown above «Удаление аккаунта». */
  team: ReactNode;
}

/** «Профиль»: the staff member's own account, one card per concern. */
export function ProfileView({ profile, lastStaff, team }: ProfileViewProps) {
  return (
    <div className={styles.sections}>
      <MainForm nickname={profile.nickname} faceitUrl={profile.faceitUrl} />
      <EmailForm current={profile.email} pending={profile.pendingEmail} />
      <PasswordForm />
      <SessionsForm />
      <div className={styles.wide}>{team}</div>
      <DeleteForm lastStaff={lastStaff} />
    </div>
  );
}
