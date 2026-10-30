import { removeMemberAction, rotateCodeAction } from "@/app/staff/actions";
import {
  ROTATE_CONFIRM_TEXT,
  TEAM_ERROR_TEXT,
  TEAM_PATH,
  removeConfirmText,
  type TeamError,
} from "@/lib/staff/team-management";
import type { MemberEntry, TeamConfirm, TeamData } from "@/lib/staff/players";
import { CopyCodeButton } from "./CopyCodeButton";
import styles from "./players.module.css";

const CANCEL_HREF = `${TEAM_PATH}#team`;

type CodeKind = "player" | "staff";

const CODES: Record<CodeKind, { title: string; label: string; hint: string }> = {
  player: {
    title: "Код игрока",
    label: "код игрока",
    hint: "Игроки вводят его в приложении Pulse, чтобы попасть в команду.",
  },
  staff: {
    title: "Код стаффа",
    label: "код стаффа",
    hint: "Даёт доступ к составу и данным игроков. Передавайте только своим.",
  },
};

function Confirm({ text, children }: { text: string; children: React.ReactNode }) {
  return (
    <div id="confirm" className={styles.confirm} role="group" aria-label="Подтверждение">
      <p className={styles.confirmText}>{text}</p>
      <div className={styles.confirmActions}>
        {children}
        <a className={styles.btn} href={CANCEL_HREF}>
          Отмена
        </a>
      </div>
    </div>
  );
}

function CodeCard({ kind, code, asking }: { kind: CodeKind; code: string; asking: boolean }) {
  const { title, label, hint } = CODES[kind];
  return (
    <div className={styles.codeCard}>
      <span className={styles.codeTitle}>{title}</span>
      <code className={styles.code}>{code}</code>
      <span className={styles.hint}>{hint}</span>
      <div className={styles.codeActions}>
        <CopyCodeButton code={code} label={label} />
        <a className={styles.btn} href={`${TEAM_PATH}?confirm=rotate-${kind}#confirm`} aria-label={`Новый ${label}`}>
          Новый код
        </a>
      </div>
      {asking && (
        <Confirm text={ROTATE_CONFIRM_TEXT}>
          <form action={rotateCodeAction}>
            <input type="hidden" name="kind" value={kind} />
            <button type="submit" className={styles.btnDanger}>
              Продолжить
            </button>
          </form>
        </Confirm>
      )}
    </div>
  );
}

function MemberList({
  title,
  members,
  selfId,
  asking,
}: {
  title: string;
  members: readonly MemberEntry[];
  selfId: string;
  asking: string | null;
}) {
  return (
    <div className={styles.members}>
      <h3 className={styles.membersTitle}>{title}</h3>
      {members.length === 0 ? (
        <p className={styles.empty}>Пока никого</p>
      ) : (
        <ul className={styles.memberList}>
          {members.map((m) => (
            <li key={m.id} className={styles.member}>
              <div className={styles.memberRow}>
                <span className={styles.memberName}>
                  {m.nickname}
                  {m.id === selfId && <em className={styles.self}> (вы)</em>}
                </span>
                <a className={styles.btn} href={`${TEAM_PATH}?confirm=remove-${m.id}#confirm`} aria-label={`Убрать из команды: ${m.nickname}`}>
                  Убрать из команды
                </a>
              </div>
              {asking === m.id && (
                <Confirm text={removeConfirmText(m.nickname)}>
                  <form action={removeMemberAction}>
                    <input type="hidden" name="memberId" value={m.id} />
                    <button type="submit" className={styles.btnDanger}>
                      Продолжить
                    </button>
                  </form>
                </Confirm>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

interface TeamManagementProps {
  team: TeamData;
  confirm: TeamConfirm | null;
  error: TeamError | null;
}

/** «Команда»: both codes, «Новый код», and the members with «Убрать из команды». Confirmation is a second page view. */
export function TeamManagement({ team, confirm, error }: TeamManagementProps) {
  const rotating = confirm?.kind === "rotate" ? confirm.code : null;
  const removing = confirm?.kind === "remove" ? confirm.member.id : null;
  return (
    <section id="team" className={styles.section} aria-labelledby="team-title">
      <h2 id="team-title" className={styles.sectionTitle}>
        Команда
      </h2>
      {error !== null && (
        <p className={styles.error} role="alert">
          {TEAM_ERROR_TEXT[error]}
        </p>
      )}
      <div className={styles.codes}>
        <CodeCard kind="player" code={team.playerCode} asking={rotating === "player"} />
        <CodeCard kind="staff" code={team.staffCode} asking={rotating === "staff"} />
      </div>
      <div className={styles.memberColumns}>
        <MemberList title="Стафф" members={team.staff} selfId={team.selfId} asking={removing} />
        <MemberList title="Игроки" members={team.players} selfId={team.selfId} asking={removing} />
      </div>
    </section>
  );
}
