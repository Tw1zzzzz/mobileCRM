"use client";

import { useEffect, useMemo, useRef, useState, useTransition } from "react";
import { saveGameAction } from "@/app/staff/actions";
import type { FormState } from "@/lib/staff/form-state";
import { checkDraft } from "./check";
import {
  isBlank, newDraft, toGameInput, withRoster, type Draft, type RosterPlayer, type RosterRules,
} from "./draft";
import { liveSummary } from "./figures";
import styles from "./game-stats.module.css";
import { InfoStep } from "./InfoStep";
import { MapsStep } from "./MapsStep";
import { PlayersStep } from "./PlayersStep";
import { clearStoredDraft, loadAttemptId, loadStoredDraft, storeAttemptId, storeDraft } from "./storage";
import { SummaryPanel, type Message } from "./SummaryPanel";

interface GameFormProps {
  teamId: string;
  roster: readonly RosterPlayer[];
  /** Today in the team's time zone, `YYYY-MM-DD`: the date of a new game. */
  today: string;
  /** A saved game to edit; null for a new one. */
  initial: Draft | null;
}

const NOT_SAVED = "Игра не сохранена — заполните пункты выше";
/** The text of a save that did not get an answer (same words as `GAME_ERRORS.failed` of the server side). */
export const SAVE_FAILED = "Не удалось сохранить игру. Повторите попытку позже";
const NEW_GAME_RULES: RosterRules = { keep: new Set(), fill: true };

/** Sends the game; a network error or a crashed action is the failed-save text, never an exception for the page. */
export async function sendGame(action: (game: unknown) => Promise<FormState>, game: unknown): Promise<FormState> {
  try {
    return await action(game);
  } catch {
    return { status: "error", message: SAVE_FAILED };
  }
}

/**
 * «Внести игру»: the three steps and the side column on one draft. The draft of a new game is kept in this
 * browser (one per team) while it is typed and goes when the game is saved or cleared; an edited game is not.
 */
export function GameForm({ teamId, roster, today, initial }: GameFormProps) {
  const editing = initial !== null;
  const [draft, setDraft] = useState<Draft>(() => initial ?? newDraft(roster, today));
  const [restored, setRestored] = useState(editing);
  const [showErrors, setShowErrors] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  const [pending, startTransition] = useTransition();
  // The id a new game is sent under: a second press after a lost answer replaces the game instead of doubling it.
  const attemptId = useRef<string | null>(null);

  // Once, after the first render: the server cannot see this browser's storage, so the stored draft comes in here.
  const restoredOnce = useRef(false);
  useEffect(() => {
    if (editing || restoredOnce.current) return;
    restoredOnce.current = true;
    const stored = loadStoredDraft(teamId);
    if (stored) {
      setDraft(withRoster(stored, roster, NEW_GAME_RULES));
      attemptId.current = loadAttemptId(teamId);
    } else {
      storeAttemptId(teamId, null);
    }
    setRestored(true);
  }, [editing, teamId, roster]);

  useEffect(() => {
    if (!restored || editing) return;
    if (isBlank(draft)) {
      clearStoredDraft(teamId);
      attemptId.current = null;
      storeAttemptId(teamId, null);
    } else {
      storeDraft(teamId, draft);
    }
  }, [draft, restored, editing, teamId]);

  const check = useMemo(() => checkDraft(draft), [draft]);
  const summary = useMemo(() => liveSummary(draft), [draft]);

  const change = (update: (current: Draft) => Draft) => {
    setDraft(update);
    setMessage(null);
  };

  function save() {
    if (check.issues.length > 0) {
      setShowErrors(true);
      setMessage({ tone: "error", text: NOT_SAVED });
      return;
    }
    setMessage(null);
    attemptId.current ??= crypto.randomUUID();
    const attempt = attemptId.current;
    if (!editing) storeAttemptId(teamId, attempt);
    const game = toGameInput(draft, attempt);
    startTransition(async () => {
      const result = await sendGame(saveGameAction, game);
      if (result.status === "error") {
        setMessage({ tone: "error", text: result.message });
        return;
      }
      attemptId.current = null;
      storeAttemptId(teamId, null);
      setShowErrors(false);
      setMessage({ tone: "ok", text: result.status === "done" ? result.message : "" });
      if (!editing) {
        clearStoredDraft(teamId);
        setDraft((current) => newDraft(roster, today, current));
      }
    });
  }

  function clear() {
    clearStoredDraft(teamId);
    attemptId.current = null;
    storeAttemptId(teamId, null);
    setShowErrors(false);
    setMessage(null);
    setDraft((current) => newDraft(roster, today, current));
  }

  return (
    <div className={styles.layout}>
      <div className={styles.steps}>
        <InfoStep draft={draft} roster={roster} onChange={change} />
        <MapsStep draft={draft} check={check} showErrors={showErrors} onChange={change} />
        <PlayersStep draft={draft} showErrors={showErrors} onChange={change} />
      </div>
      <SummaryPanel
        summary={summary}
        check={check}
        message={message}
        pending={pending}
        editing={editing}
        onSave={save}
        onClear={clear}
      />
    </div>
  );
}
