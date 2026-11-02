# T07 AC-8.8 真实 payout 即批次、AC-8.6 退款走真实 Stripe、AC-8.9 对账接真实数据

## 背景
本任务在队列里标了 `[WAIT-PAYOUT]`：执行者确认 `check-payout.sh` 返回 0（至少一笔自动 payout 已 paid 且 reconciliation completed）后才会调你。stripe-integration.md 第 2 节（payout 事件、明细就绪）、第 3 节（出站退款）、第 9.4、9.7 节；AC-8.6、AC-8.8、AC-8.9；P3 退款 API、P2 对账接口（照 `scripts/smoke/p2.py`、`p3.py` 的调用方式）。

## 范围
1. 先跑 `sh scripts/sandbox/catchup.sh`，确保 payout 事件和其包含的支付都进了账本。
2. `accept.sh --only ac-8.8`：对 `check-payout.sh` 给出的每个 po_：`GET /api/batches/{po_}` 存在且已核对（`BATCH_SETTLED` 分录存在）；批次净额 = Stripe payout 金额；批次行逐行（txn_、类型、金额、手续费、净额）与 Stripe `GET /v1/balance_transactions?payout=` 完整列表相等（`type=payout` 行按设计跳过）；之后 bank sync＋导入后批次 `BANKED`。模拟银行在下一个工作日才入账：如果其他都通过、只差 BANKED，输出“等待银行入账”并退出 75。HELD 的批次要打印 hold_reason 和原因对象编号并判 FAIL——不要为了通过而改账本或数据。
3. `accept.sh --only ac-8.6`（需要至少一个已 SETTLED 批次里的支付，否则退出 75）：
   a. 对一笔已结算支付经 app 退款 API 退 100 分（幂等键固定），等到 `SUCCEEDED`；Stripe `GET /v1/refunds?charge=` 翻完分页，恰好一笔且 `metadata.ledgerlab_refund_id` 等于本地退款号。
   b. relay 设出站规则 `POST /v1/refunds` DROP_RESPONSE times=1；对另一笔已结算支付退 100 分；断言本地命令先变 `UNKNOWN`（或在你观察到之前已被查询恢复——那就用命令尝试记录证明经历过 UNKNOWN／丢回执），最终退款 `SUBMITTED` 或 `SUCCEEDED`，Stripe 上这笔 charge 只有一笔退款；relay stats 里 DROP_RESPONSE 计数 +1。恢复空计划。
   c. 记录 Stripe 返回的退款状态（是否出现 pending）回写第 7 节第 4 条。
4. `accept.sh --only ac-8.9`：用 P2 的处理方快照导入＋对账运行接口，以窗口起点到当前整点为区间、截止时点为当前整点：断言快照存档有哈希和截止时点；对账结果里每条差异的对象编号都是 Stripe 前缀（`po_`/`txn_`/`ch_`/`re_`/`evt_`），逐条打印；对同一快照重跑对账结果相同（可重放）。差异本身不判失败（真实数据可能有真差异，例如探测阶段的对象），但必须能追到 Stripe 编号。
5. 第 7 节第 1、8 条（自动 payout、reconciliation_completed）回写实测结论（包括从 charge 到 payout、到 completed 各用了多久）；第 9.9 节补已知差异。

## 执行者和 Codex 都要实际跑
`sh scripts/sandbox/accept.sh --only ac-8.8`、`--only ac-8.6`、`--only ac-8.9`。退款预算 2 笔（重跑最多 4）。ac-8.8 因银行入账退出 75 时，Codex 在最后输出写明，不算失败。

## 可改范围
`scripts/sandbox/**`、`docs/design/stripe-integration.md`。真实运行暴露出主应用缺陷时：先写一个能复现的失败测试（fake Stripe 或录制夹具），然后停下来问，附证据——不要直接改主应用代码。

## 完成标准
`python -m py_compile scripts/sandbox/*.py`；三段真实运行结果（0 或 ac-8.8 的 75）。本地提交（`Task: T07`）。

## 执行者验证
如果 ac-8.8 是 75（等银行），每 2 小时跑一次 `sh scripts/sandbox/catchup.sh && sh scripts/sandbox/accept.sh --only ac-8.8`，直到 0（最多 2 天），结果追加进 T07 报告；期间可以继续 T08。
