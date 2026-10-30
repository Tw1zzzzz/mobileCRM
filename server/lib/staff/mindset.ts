/**
 * Scores of the «Игровой характер» test, computed from a player's answers so that staff get the scores and never
 * the answers. Mirrors `MindsetMath.scores` and the question list in core (model/Survey.kt, SurveyTexts.kt).
 */

export type MindsetTrait = "composure" | "resilience" | "focus" | "teamwork" | "leadership" | "drive";

interface Question {
  id: string;
  trait: MindsetTrait;
  /** Agreeing means a lower trait score. */
  reversed: boolean;
}

const TRAITS: readonly MindsetTrait[] = ["composure", "resilience", "focus", "teamwork", "leadership", "drive"];

/** q1–q6 first items, q7–q12 their reversed pairs, and so on, in the order of the app's `mindsetQuestions`. */
const QUESTIONS: readonly Question[] = Array.from({ length: 24 }, (_, i) => ({
  id: `q${i + 1}`,
  trait: TRAITS[i % 6],
  reversed: Math.floor(i / 6) % 2 === 1,
}));

export type MindsetScores = Partial<Record<MindsetTrait, number>>;

/** 0…100 per trait: mean of the 1…5 answers (reversed items flipped), rescaled. Unusable answers are skipped. */
export function mindsetScores(answers: unknown): MindsetScores {
  const given = typeof answers === "object" && answers !== null ? (answers as Record<string, unknown>) : {};
  const scores: MindsetScores = {};
  for (const trait of TRAITS) {
    const values: number[] = [];
    for (const q of QUESTIONS) {
      if (q.trait !== trait) continue;
      const raw = given[q.id];
      if (typeof raw !== "number" || !Number.isInteger(raw) || raw < 1 || raw > 5) continue;
      values.push(q.reversed ? 6 - raw : raw);
    }
    if (values.length === 0) continue;
    const mean = values.reduce((sum, v) => sum + v, 0) / values.length;
    scores[trait] = Math.round(((mean - 1) / 4) * 100);
  }
  return scores;
}
