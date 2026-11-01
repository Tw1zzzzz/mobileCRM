import { CardFrame } from "@/components/staff/card/CardFrame";
import { Header } from "@/components/staff/Header";
import { NutritionFeed } from "@/components/staff/players/NutritionFeed";
import { PeriodChips, Roster } from "@/components/staff/players/Roster";
import { defaultDeps } from "@/lib/deps";
import { loadCard } from "@/lib/staff/card";
import { requireStaffContext } from "@/lib/staff/current";
import { isNutritionSection, loadNutrition } from "@/lib/staff/nutrition";
import type { SearchParams } from "@/lib/staff/overview";
import { loadPlayers } from "@/lib/staff/players";

/**
 * «Игроки»: today's readiness of the roster and selected player's card (`?player=&period=&tab=&type=&src=&day=`).
 * `?section=nutrition&who=&days=&photos=&meal=` shows «Питание команды» instead of the roster and the card.
 */
export default async function PlayersPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  // Layouts do not protect page data: the page checks the session itself.
  const ctx = await requireStaffContext();
  const params = await searchParams;
  const deps = defaultDeps();
  if (isNutritionSection(params)) {
    const nutrition = await loadNutrition(deps, ctx, params);
    return (
      <>
        <Header title="Игроки" />
        <NutritionFeed data={nutrition} />
      </>
    );
  }
  const [page, card] = await Promise.all([loadPlayers(deps, ctx, params), loadCard(deps, ctx, params)]);
  const selection = card.kind === "card" ? card.params : null;
  return (
    <>
      <Header title="Игроки">{selection && <PeriodChips params={selection} />}</Header>
      <Roster roster={page.roster} params={selection} />
      <CardFrame card={card} />
    </>
  );
}
