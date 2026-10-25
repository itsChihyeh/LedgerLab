export interface AsOf {
  seq: number;
  at: string;
}
export interface Payment {
  id: string;
  processorPaymentRef: string;
  amount: number;
  currency: string;
  status: string;
  expectedFee: number;
}
export interface PaymentList {
  payments: Payment[];
  asOf: AsOf;
}
export interface PaymentDetail extends Payment {
  refunds: Refund[];
  refundable_remaining: number;
  actualFee: number | null;
  net: number | null;
  batch: PaymentBatch | null;
  bankDeposit: BankDeposit | null;
  postings: {
    entryId: string;
    entryType: string;
    lineNo: number;
    accountCode: number;
    accountName: string;
    side: string;
    amount: number;
  }[];
  deliveries: Delivery[];
  asOf: AsOf;
}

export interface Delivery {
  id: number;
  eventId: string;
  eventType: string;
  outcome: string;
  status: string;
  result: string | null;
  receivedAt: string;
  subject: "payment" | "batch" | null;
}

export interface EventDetail {
  eventId: string;
  eventType: string;
  apiVersion: string;
  livemode: boolean;
  occurredAt: string;
  receivedAt: string;
  status: string;
  result: string | null;
  subject: "payment" | "batch" | null;
  subjectId: string | null;
  deliveries: Delivery[];
  asOf: AsOf;
}

export type JsonValue =
  | null
  | boolean
  | number
  | string
  | JsonValue[]
  | { [key: string]: JsonValue };
export interface PaymentBatch {
  batchId: string;
  status: string;
  settlementDate: string | null;
  arrivalDate: string | null;
}
export interface BankDeposit {
  bankTxnId: string;
  amount: number;
  bookingDate: string;
}

export interface Batch {
  batchId: string;
  payoutAmount: number;
  currency: string;
  automatic: boolean;
  method: string;
  settlementDate: string | null;
  arrivalDate: string | null;
  gross: number | null;
  fees: number | null;
  refunds: number | null;
  payoutFailuresReturned: number | null;
  net: number | null;
  status: string;
  holdReason: string | null;
  sourceEventId: string;
  linesFetchId: number | null;
  occurredAt: string;
  recordedAt: string;
  updatedAt: string;
}

export interface BatchLine {
  batchId: string;
  lineNo: number;
  lineType: string;
  processorRef: string;
  balanceTxnId: string;
  gross: number;
  fee: number;
  net: number;
  occurredAt: string;
  recordedAt: string;
}

export interface BatchTransition {
  id: number;
  subjectId: string;
  fromStatus: string | null;
  toStatus: string;
  evidenceType: string;
  evidenceId: string;
  occurredAt: string;
  recordedAt: string;
  holdReason: string | null;
  linesFetchId: number | null;
}

export interface Entry {
  id: string;
  seq: number;
  entryType: string;
  sourceKey: string;
  postingCount: number;
  totalAmount: number;
  occurredAt: string;
  recordedAt: string;
  subjectType: string;
  subjectId: string;
  reversesEntryId: string | null;
  correctionId: string | null;
}

export interface BankLine {
  importOrder: number;
  bankTxnId: string;
  version: number;
  fileId: string;
  direction: string;
  currency: string;
  status: string;
  amount: number;
  reference: string;
  returnOf: string | null;
  reasonCode: string | null;
  bookingDate: string;
  valueDate: string;
  memo: string;
  matchStatus: string;
  matchedBatchId: string | null;
  waitUntil: string | null;
  recordedAt: string;
}

export interface StatementLine {
  fileId: string;
  lineSeq: number;
  statementLineId: string;
  lineType: string;
  processorRef: string;
  batchId: string;
  gross: number;
  fee: number;
  net: number;
  occurredAt: string;
  settlementDate: string;
  memo: string;
  recordedAt: string;
}

export interface Issue {
  id: string;
  category: string;
  subjectType: string;
  subjectId: string;
  detectedBy: string;
  firstRunId: string | null;
  lastRunId: string | null;
  status: string;
  expected: JsonValue;
  actual: JsonValue;
  evidenceRefs: JsonValue;
  explanation: string | null;
  resolutionCorrectionId: string | null;
  recordedAt: string;
  updatedAt: string;
  fingerprint: string | null;
  reopenCount: number;
}

export interface Finding {
  id: string;
  runId: string;
  category: string;
  subjectType: string;
  subjectId: string;
  expected: JsonValue;
  actual: JsonValue;
  evidenceRefs: JsonValue;
  fingerprint: string;
  dueAt: string | null;
  issueId: string;
  recordedAt: string;
}

export interface IssueTransition {
  id: string;
  issueId: string;
  fromStatus: string | null;
  toStatus: string;
  runId: string | null;
  reason: string;
  explanation: string;
  evidenceRefs: JsonValue;
  recordedAt: string;
}

export interface Run {
  id: string;
  kind: string;
  status: string;
  inputFileIds: JsonValue;
  inputHashes: JsonValue;
  ruleVersion: string;
  cutoffAt: string;
  evidenceAsOf: string;
  startedAt: string;
  finishedAt: string | null;
  summary: JsonValue;
  recordedAt: string;
}

export interface Account {
  code: number;
  name: string;
  balance: number;
}
export interface Overview {
  refund_payable: number;
  awaiting_funds_count: number;
  awaiting_funds_amount: number;
  merchant_debt: number;
  accounts: Account[];
  inTransit: number;
  bankCash: number;
  merchantAvailable: number;
  merchantOwes: number;
  unappliedCash: number;
  openIssuesByCategory: Record<string, number>;
  batchesByStatus: Record<string, number>;
  asOf: AsOf;
}
export interface BatchList {
  batches: Batch[];
  asOf: AsOf;
}
export interface BatchDetail {
  batch: Batch;
  lines: BatchLine[];
  history: BatchTransition[];
  entries: Entry[];
  postings: PaymentDetail["postings"];
  bankLines: BankLine[];
  deliveries: Delivery[];
  asOf: AsOf;
}
export interface BankDetail {
  bankLine: BankLine;
  versions: BankLine[];
  entries: Entry[];
  postings: PaymentDetail["postings"];
  asOf: AsOf;
}
export interface StatementDetail {
  statementLine: StatementLine;
  asOf: AsOf;
}
export interface IssueList {
  issues: Issue[];
  asOf: AsOf;
}
export interface AmountComparison {
  metric: string;
  statementLineId: string | null;
  expectedAmount: number;
  actualAmount: number;
  difference: number;
}
export interface IssueReferences {
  refundIds: string[];
  batchIds: string[];
  statementLines: {
    fileId: string;
    lineSeq: number;
    statementLineId: string;
  }[];
  eventIds: string[];
  entryIds: string[];
  bankTxnIds: string[];
  paymentIds: string[];
}
export interface IssueDetail {
  issue: Issue;
  amounts: AmountComparison[];
  history: IssueTransition[];
  findings: Finding[];
  references: IssueReferences;
  asOf: AsOf;
}
export interface RunList {
  runs: Run[];
  asOf: AsOf;
}
export interface AcceptIssueRequest {
  reason: string;
}
export interface ResolveIssueRequest {
  note: string;
}

export interface Refund {
  refund_id: string;
  payment_ref: string;
  merchant_refund_ref: string;
  amount: number;
  status: string;
  accepted_at: string;
  queue_seq: number;
  queue_position: number | null;
  processor_refund_ref: string | null;
  command_status: string | null;
  command_attempts: number | null;
  submitted_at: string | null;
  resolved_at: string | null;
  cancel_reason: string | null;
  failure_reason: string | null;
}
export interface RefundDetail {
  refund: Refund;
  history: {
    id: number;
    from_status: string | null;
    to_status: string;
    evidence_source: string;
    evidence_ref: string;
    occurred_at: string;
    recorded_at: string;
  }[];
  commands: {
    command_id: string;
    status: string;
    attempts: number;
    next_attempt_at: string;
    first_sent_at: string | null;
    created_at: string;
    recorded_at: string;
    last_outcome: string | null;
  }[];
  attempts: {
    command_id: string;
    attempt_no: number;
    sent_at: string;
    finished_at: string;
    recorded_at: string;
    response_status: number | null;
    result_code: string;
  }[];
  entries: Entry[];
  postings: PaymentDetail["postings"];
  asOf: AsOf;
}
export interface RefundRequest {
  key: string;
  body: { payment_ref: string; merchant_refund_ref: string; amount: number };
}
export interface RefundResponse {
  refund_id: string;
  status: string;
  amount: number;
}
