export const issueCategoryLabels: Record<string, string> = {
  UNSUPPORTED_BATCH_LINE: "批次含不支持的行",
  REFUND_NOT_IN_STATEMENT: "退款未进入结算单",
  REFUND_NOT_EXECUTED: "退款尚未执行",
  REFUND_AWAITING_FUNDS_AGED: "退款等待资金超时",
  REFUND_STATE_CONFLICT: "退款状态冲突",
  EVENT_PARKED_AGED: "事件挂起超时",
};

export function issueCategoryLabel(category: string): string {
  return issueCategoryLabels[category] ?? category;
}
