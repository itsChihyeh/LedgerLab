export const issueCategoryLabels: Record<string, string> = {
  UNSUPPORTED_BATCH_LINE: "批次含不支持的行",
};

export function issueCategoryLabel(category: string): string {
  return issueCategoryLabels[category] ?? category;
}
