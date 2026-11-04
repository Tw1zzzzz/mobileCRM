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
  /** The team's name, for the role line of the summary. */
  teamName: string;
  lastStaff: boolean;
  /** The «Команда» section (codes and members), shown above «Удаление аккаунта». */
  team: ReactNode;
}

/** A labelled pair of cards; on a narrow screen the cards stack. */
function Group({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className={styles.group}>
      <p className={styles.groupTitle}>{title}</p>
      <div className={styles.pair}>{children}</div>
    </div>
  );
}

/**
 * «Профиль»: who is signed in, then the account and security cards two by two (equal rows, so nothing is scattered
 * over a wide monitor), the team, and deletion last.
 */
export function ProfileView({ profile, teamName, lastStaff, team }: ProfileViewProps) {
  const initials = Array.from(profile.nickname.trim()).slice(0, 2).join("").toUpperCase();
  return (
    <div className={styles.sections}>
      <div className={styles.summary}>
        <span className={styles.summaryAvatar} aria-hidden="true">{initials}</span>
        <div className={styles.summaryText}>
          <span className={styles.summaryName}>{profile.nickname}</span>
          <span className={styles.summaryMeta}>{profile.email}</span>
        </div>
        <span className={styles.summaryRole}>Staff · {teamName}</span>
      </div>
      <Group title="Аккаунт">
        <MainForm nickname={profile.nickname} faceitUrl={profile.faceitUrl} />
        <EmailForm current={profile.email} pending={profile.pendingEmail} />
      </Group>
      <Group title="Безопасность">
        <PasswordForm />
        <SessionsForm />
      </Group>
      <div className={styles.group}>{team}</div>
      <DeleteForm lastStaff={lastStaff} />
    </div>
  );
}
