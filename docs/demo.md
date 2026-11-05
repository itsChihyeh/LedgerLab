# Three-minute demo

Every step below replays a real run of the automated browser tests: the real app, real PostgreSQL, signed webhooks and bank files. There is no hosted version. Nothing here touches Stripe or real money. The UI labels are in Chinese, so the script gives the English meaning.

It does not show the investigation agent or deployment, because neither has been built.

## Prepare (about 5 minutes, off camera)

You need JDK 25, Node 24, Docker running and Git Bash on Windows. Run this from the repository root on `main`:

```sh
./mvnw -f simulator/pom.xml clean package -DskipTests
cd frontend
npm ci
npx playwright install chromium
for s in sample-a-1-3 refund-concurrent-6000 payout-recovery ac-5-3; do
  LEDGERLAB_E2E_SCENARIO=$s VITE_E2E_REFUNDS=true VITE_SIMULATOR_MODE=true \
    node node_modules/@playwright/test/cli.js test --trace on
done
cd ..
sh scripts/smoke-p5.sh | grep '^PASS' > target/smoke-p5-pass.txt
cd frontend
```

Rebuild the simulator as shown even if a jar exists. A jar left over from branch `p6` contains a migration that `main` cannot run, and the payout scenario will fail to start.

Open each trace in its own window, then arrange them in the order of the scenes:

```sh
show() { node node_modules/@playwright/test/cli.js show-trace "$(find test-results/$1 -path "*$2*" -name trace.zip)" & }
show sample-a-1-3 shows-one
show sample-a-1-3 settlement
show refund-concurrent-6000 refunds
show payout-recovery payouts
show ac-5-3 trace-B1
```

In Trace Viewer, click an action on the left to see the page at that moment. Type in "Filter actions" to find a step quickly.

## Script

### 0:00 README (20 seconds)

Show the top of the repository's README.

> LedgerLab is a payment settlement sandbox. Processor webhooks go onto an append-only double-entry ledger, which is reconciled against processor and bank records. All the data is synthetic. I'll show four things that break naive payment systems, using recorded browser tests against the real app and a real PostgreSQL database.

### 0:20 Duplicate webhooks (35 seconds)

Trace `ac_1_14 shows one capture and all three delivery results`. Click the last `Expect` before the page reload, and point at the delivery table (投递记录) and the postings (账本 posting).

> Here's a CAD 100 payment. The webhook was delivered three times: the original, an exact repeat, and the same fact under a new event ID. The delivery table shows all three, marked new, repeat and duplicate fact. The ledger has only two postings: debit processor receivable, credit merchant pending.

Trace `ac_1_11 ac_2_16 S3 amounts`. Filter for `posting`, and show the 9 postings after the replay.

> After settlement and the bank deposit, this payment has nine postings. Now we replay the batch confirmation with a new event ID. The delivery is logged as a duplicate, the nine postings are identical, and the ledger sequence hasn't moved.

### 0:55 Concurrent refunds (30 seconds)

Trace `ac_3_15 concurrent refunds 6000`. Filter for `同时发出` ("send both at once"), click it, then click the next `Expect` and show the request log (并发请求日志).

> The merchant has CAD 96.80 available from that payment. We send two CAD 60 refunds at the same moment, each with its own idempotency key. One gets 201. The other gets 409, refund exceeds remaining. Merchant available ends at CAD 36.80. Each request takes a row lock on a single funds-lock row and then on the payment, inside one transaction, so the second one waits and sees the first one's result.

### 1:25 A lost bank response (40 seconds)

Trace `ac_7_19 lost bank response`. Step through: the 100.00 attempt is refused; 96.80 is submitted; the status is UNKNOWN (状态UNKNOWN); then ACCEPTED; then PAID.

> Now payouts. The first attempt is for more than the merchant has, and it's refused. Then we tell the simulated bank to execute a payment and drop the response. We can't know whether the money moved, so the payout goes to UNKNOWN and the funds stay reserved. They aren't released and they aren't spent. The worker queries the bank using the same request ID, finds the payment and moves it to ACCEPTED. Cash only moves when the bank statement shows the debit, and then it's PAID. The bank's own records show exactly one payment.

### 2:05 Reconciliation (30 seconds)

Trace `ac_5_15 trace B1 discrepancy`. Click the `BATCH_TOTAL_MISMATCH` link step, show the amount comparison (金额比较), then one evidence link, then the accept step with its reason (接受原因).

> Reconciliation doesn't trust our own books. This fixture has seeded discrepancies with a known answer. This one is a batch total mismatch: 25,096 cents against 23,639, a gap of CAD 14.57. Every finding links to its evidence, down to the statement line, the original event and the bank line. An operator can accept it, but only with a reason, and accepting it never edits the ledger.

### 2:35 Evidence (20 seconds)

Show a terminal with this output, already prepared:

```sh
cat ../target/smoke-p5-pass.txt
```

Then show `docs/acceptance-criteria.md` next to a `git grep -n ac_3_2 src/test` result.

> This is a three-process run: the app, the simulator and the database, ending with a three-way reconciliation with zero findings. Acceptance criteria map to tests by number, so anyone can find the test behind a claim. Randomized model tests run a thousand event sequences per model against a separate reference model. The README has the commands, and the counts I measured.

### 2:55 Close (5 seconds)

> This project is a sandbox, not a production system. Most of the code was written by AI agents; the repository says exactly who did what.

If you run over, cut the first trace in the Duplicate webhooks scene.

## Check before recording

- Only say numbers you can see on screen or in the README.
- If asked about branch `p6`, it holds the Stripe test mode work, which is partly done. It isn't in this demo.
- Be ready to open `RefundApi`, `JdbcLedger.lockBalances` and `PayoutCommandHandler` if someone asks to see the code.
