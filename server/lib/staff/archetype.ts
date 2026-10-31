import { parseInstant } from "@/lib/staff/metrics";
import {
  BALANCED_SPREAD,
  BALANCED_TEXTS,
  MINDSET_TRAITS,
  STRENGTH_THRESHOLD,
  TRAIT_TEXTS,
  type MindsetScores,
  type MindsetTrait,
} from "@/lib/staff/mindset";

/**
 * The «Архетип» of the player card: the headline of the latest finished «Игровой характер» run, decided like the
 * app's `MindsetProfile` / `MindsetArchetype.forTrait` (core model/Survey.kt). Pinned to core by
 * `test/fixtures/staff-archetype.json` and `StaffArchetypeParityTest`.
 */

/** The most strengths shown as tags (the board draws three; the app shows two). */
export const MAX_TAGS = 3;

export interface Archetype {
  title: string;
  summary: string;
  /** Short titles of the traits at 70 or more, strongest first. */
  tags: string[];
}

/**
 * Leading trait = highest score, the earlier trait of the app's list on a tie; «Ровный по всем чертам» when the
 * highest and lowest differ by less than 12. A trait without a score counts as 0, as in the app. Null without a run.
 */
export function archetypeOf(scores: MindsetScores | null): Archetype | null {
  if (scores === null) return null;
  const value = (trait: MindsetTrait): number => scores[trait] ?? 0;
  // Array.prototype.sort is stable, so equal scores keep the trait order.
  const ranked = [...MINDSET_TRAITS].sort((a, b) => value(b) - value(a));
  const spread = value(ranked[0]) - value(ranked[ranked.length - 1]);
  const texts = spread < BALANCED_SPREAD ? BALANCED_TEXTS : TRAIT_TEXTS[ranked[0]];
  return {
    title: texts.title,
    summary: texts.summary,
    tags: ranked
      .filter((trait) => value(trait) >= STRENGTH_THRESHOLD)
      .slice(0, MAX_TAGS)
      .map((trait) => TRAIT_TEXTS[trait].shortTitle),
  };
}

function finishedScores(scores: unknown): MindsetScores | null {
  if (typeof scores !== "object" || scores === null) return null;
  const held = scores as Record<string, unknown>;
  const result: MindsetScores = {};
  for (const trait of MINDSET_TRAITS) {
    const v = held[trait];
    if (typeof v !== "number" || !Number.isFinite(v)) return null;
    result[trait] = v;
  }
  return result;
}

/**
 * The scores of the latest finished run in a `player-survey` document as `access.ts` hands it out
 * (`{ mindset: [{ id, date, scores }] }`). A run is finished when it has a score for all six traits; the latest is
 * the greatest date (the later entry on the same instant; a date that cannot be read is older than any real one).
 */
export function latestMindsetScores(survey: unknown): MindsetScores | null {
  const runs = typeof survey === "object" && survey !== null ? (survey as { mindset?: unknown }).mindset : undefined;
  if (!Array.isArray(runs)) return null;
  let best: { at: number; scores: MindsetScores } | null = null;
  for (const run of runs) {
    if (typeof run !== "object" || run === null) continue;
    const scores = finishedScores((run as { scores?: unknown }).scores);
    if (scores === null) continue;
    const at = parseInstant((run as { date?: unknown }).date)?.getTime() ?? Number.NEGATIVE_INFINITY;
    if (best === null || at >= best.at) best = { at, scores };
  }
  return best === null ? null : best.scores;
}
