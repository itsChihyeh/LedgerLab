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
  postings: {
    entryId: string;
    entryType: string;
    lineNo: number;
    accountCode: number;
    accountName: string;
    side: string;
    amount: number;
  }[];
  deliveries: {
    eventId: string;
    eventType: string;
    outcome: string;
    status: string;
    result: string | null;
    receivedAt: string;
  }[];
  asOf: AsOf;
}
