export function formatCad(cents: number): string {
  if (!Number.isSafeInteger(cents))
    throw new RangeError("Amount must be safe integer cents");
  const absolute = BigInt(cents < 0 ? -cents : cents);
  return `CAD ${cents < 0 ? "-" : ""}${absolute / 100n}.${String(absolute % 100n).padStart(2, "0")}`;
}
