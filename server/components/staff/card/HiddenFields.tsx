/** The card selection (and the player) every notes form posts back; the server validates all of it again. */
export function HiddenFields({ pairs }: { pairs: readonly (readonly [string, string])[] }) {
  return (
    <>
      {pairs.map(([name, value]) => (
        <input key={name} type="hidden" name={name} value={value} />
      ))}
    </>
  );
}
