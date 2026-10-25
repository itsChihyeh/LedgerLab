export function formatCad(cents: number): string {
  if (!Number.isSafeInteger(cents))
    throw new RangeError("Amount must be safe integer cents");
  const absolute = BigInt(cents < 0 ? -cents : cents);
  return `CAD ${cents < 0 ? "-" : ""}${absolute / 100n}.${String(absolute % 100n).padStart(2, "0")}`;
}

export function parseCad(input: string): number {
  if (!/^(0|[1-9]\d{0,5})\.\d{2}$/.test(input))
    throw new Error(
      "请输入 CAD 元，保留两位小数，例如 20.00（100.5 请写为 100.50）",
    );
  const [dollars, cents] = input.split(".");
  const amount = BigInt(dollars) * 100n + BigInt(cents);
  if (amount < 1n || amount > 99999999n)
    throw new Error("退款金额须在 CAD 0.01 至 CAD 999999.99 之间");
  return Number(amount);
}
