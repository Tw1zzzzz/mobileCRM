import type { LineupFilter } from "@/lib/staff/lineups";

/** The page's filters as hidden fields, so a server action can send the person back to the same list. */
export function FilterFields({ filters, prefix = "" }: { filters: LineupFilter; prefix?: string }) {
  return (
    <>
      {(["q", "map", "type", "side"] as const).map((name) => (
        <input key={name} type="hidden" name={`${prefix}${name}`} value={filters[name]} />
      ))}
      {filters.favorites && <input type="hidden" name={`${prefix}fav`} value="1" />}
    </>
  );
}
