# ADR-0006 提现结果未知不释放资金

状态：已采纳（P0）。来源：复核 S1，撤销 R7 的“超时自动退回”。

## 背景
调用银行超时，可能是银行没收到，也可能是银行已经付了款、只是回执丢了。超时就把钱退回商户可用，商户可以再提一次，钱就付了两次（money-model 样例 B 的反例）。

## 决定
- 超时、断连、5xx 一律进入 `UNKNOWN`，资金继续留在 `payout_in_transit`。
- 用同一个 `bank_request_id`（= 提现编号）重试或查询，银行按它去重。
- 只有银行**确认**拒绝或取消、且流水里没有出账时，才记 `PAYOUT_FAILED` 释放资金。
- 出账只认银行流水；已出账后又被退回记 `PAYOUT_RETURNED`。
- 冲突证据（已判失败又出现出账）不自动处理，开差异走受限纠错。
- 退款命令同理。

## 依据
Kill Bill `IncompletePaymentTransactionTask`：查不清就保持未知，不自动转失败；moov-io/paygate 的退回处理。TigerBeetle 的 pending 超时释放只适用于内部预留，不能外推到外部付款。

## 后果
可能有资金长期卡在 `UNKNOWN`；靠老化检查报警和人工查询解决，这是有意的取舍：宁可慢，不可付两次。

## 补充（2026-11-02，P5 开工前置：提现的完整设计）

Claude 按用户授权定稿。本节是 P5 的设计正本；与上文及 state-machines 第 3 节冲突处以本节为准。纠错部分见 ADR-0011 第 8 节。

### 1. 模块与对象

- 新模块 `payouts`（依赖 `ledger`、`shared`、`outbound`、`refunds`（只为提现失败/退回后调用 `RefundQueue.release`）、`reconciliation` 的公开差异入口）。`settlement` 的银行导入通过 `payouts` 的公开窄接口 `PayoutBankEvidence` 应用出账/退回证据（方向与 P3 的 `RefundEvidence` 相同）。
- `core.payout`（数据字典已列字段）补：`currency`（固定 `CAD`）、`reserved_at`、`bank_execution_date` NULL、`paid_at` NULL、`failed_at` NULL、`returned_at` NULL、`return_reason` NULL、`updated_at`。`id` = `mpo_` ULID = `bank_request_id`。`merchant_payout_ref` UNIQUE（永久业务编号）。`amount` 1～99999999。
- `core.payout_status_history`（只追加）：`payout_id`、`from_status` NULL、`to_status`、`evidence_type`（`api`/`bank_api`/`bank_txn`/`correction`/`operator`）、`evidence_id`、`occurred_at`、`recorded_at`。
- `recon.bank_line` 增加 `matched_payout_id` NULL（FK `core.payout`），`match_status` 增加 `PAYOUT_PAID`（DEBIT 出账已记 `PAYOUT_PAID`）、`PAYOUT_RETURNED`（CREDIT 退回已记 `PAYOUT_RETURNED`）。`app` 可 UPDATE 的列加 `matched_payout_id`。CHECK：这两个状态要求 `matched_payout_id` 非空；`PAYOUT_PAID` 只限 DEBIT、`PAYOUT_RETURNED` 只限 CREDIT。
- 出站命令 `SUBMIT_PAYOUT`（与受理同事务建，`request_id = subject_id = mpo_…`）和 `QUERY_PAYOUT`（运营查询时建）沿用 `io.outbound_command`，不新增命令表。

### 2. 受理 `POST /api/payouts`

- 请求 `{"amount": <整数分>, "merchantPayoutRef": "<1～64 可见 ASCII>"}`，头 `Idempotency-Key` 必填。三层幂等完全照退款（ADR-0009、P3 T05）：HTTP 幂等记录、`merchant_payout_ref` 永久业务编号返回 `initial_result`、数据库唯一约束；4xx 不留任何行（包括幂等记录）。同一业务编号、不同金额 → 409 `PAYOUT_REF_CONFLICT`。
- 金额：缺失、非整数、字符串、≤0、>99999999 → 400 `INVALID_AMOUNT`（AC-7.20）。
- 事务：资金锁 → 按代码升序读锁 1101、1103、2102、2104、2105 的余额（`Ledger` 公开读接口，已有 `T04b` 余额读取）→ 门槛（money-model 4.2）：先查 `P ≤ 2102`，不过 → 409 `INSUFFICIENT_FUNDS` `reason=MERCHANT_BALANCE`；再查 `P ≤ free_cash`，不过 → `reason=FREE_CASH`。两条都不过时报 `MERCHANT_BALANCE`。
- 通过：插 payout（`RESERVED`）、历史、`PAYOUT_RESERVED`（借 2102 / 贷 2104，`source_key=payout:{id}:reserved`）、`SUBMIT_PAYOUT` 命令，同一事务。201 响应体 `{payoutId, status:"RESERVED", amount, merchantPayoutRef, reservedAt, bankRequestId}` 存为 `initial_result`。
- 读接口：`GET /api/payouts`（按 `reserved_at` 降序分页）、`GET /api/payouts/{id}`（payout、状态历史、出站命令与尝试的脱敏摘要、关联银行流水行、分录、差异）。`GET /api/overview` 增加 `payoutInTransit`、`freeCash`。

### 3. 模拟银行的出站付款接口（模拟器 V6 迁移）

- `POST /sim/bank/payments` `{"requestId":"mpo_…","amount":9680,"currency":"CAD","destination":"MERCHANT-001"}`：
  - 按 `requestId` **永久**去重：已存在且金额、币种、收款方相同 → 200 返回原付款当前状态；内容不同 → 409 `REQUEST_CONFLICT`，不改原付款。
  - 新请求：建 `sim_bank.outgoing_payment`（`request_id` PK、`payment_id bpm_…`、金额、状态、`execution_date`、`plan` 冻结），默认状态 `ACCEPTED`，`execution_date` = 模拟当日之后的下一个银行工作日；201 `{paymentId, requestId, status, executionDate}`。
- `GET /sim/bank/payments/{requestId}` → 200 当前状态（`ACCEPTED`/`EXECUTED`/`REJECTED`/`RETURNED`，带 `reason`），未知 → 404 `NOT_FOUND`。
- 执行：模拟时钟推进到 `execution_date` 时（与现有入账同一调度点）状态 → `EXECUTED`，在 `sim_bank.txn` 写一行 DEBIT、`reference = requestId`、`bank_txn_id = bt_{requestId}_1`、memo `Payout {requestId}`，余额减少。余额不足时不执行，状态 `REJECTED reason=INSUFFICIENT_FUNDS`（不产生流水）。
- 退回：故障 `RETURN_AFTER_DAYS n` 的付款在执行后第 n 个工作日写一行 CREDIT，`reference = requestId`、`bank_txn_id = bt_{requestId}_R`、memo `RETURN ACCOUNT_CLOSED`，状态 `RETURNED reason=ACCOUNT_CLOSED`。
- 故障（`PUT /sim/bank/plan` 已有的计划表扩展 `target_ref = mpo_…` 或 `*`；接收时冻结在付款上）：
  - `TIMEOUT`：请求不落库，响应挂起到客户端超时（测试用短超时）；
  - `EXECUTE_DROP_RESPONSE`：落库并照常执行，但这次 HTTP 响应以连接中断结束（AC-7.8）；
  - `UNAVAILABLE n`：之后 n 次 POST/GET 都 503，不落库（AC-7.7 用 n ≥ 10）；
  - `REJECT reason`：落库为 `REJECTED`（不执行、无流水），201 响应即带 `REJECTED`；
  - `REJECT_ON_QUERY reason`：首次 POST 照常 `ACCEPTED`，但永不执行，下一次 GET 返回 `REJECTED`（AC-7.9 用：先超时进 UNKNOWN，再查到拒绝）；
  - `EXECUTE_AFTER_REJECT`：先按 `REJECT` 回复，之后仍在执行日写出账（AC-7.11 冲突证据用）；
  - `RETURN_AFTER_DAYS n`：见上。
- `sim_truth` 记每次付款请求、执行、退回、故障注入；`GET /sim/bank/payments` 列出全部付款供测试和演示断言“只有一笔付款”。

### 4. 出站处理（`SUBMIT_PAYOUT`、`QUERY_PAYOUT`）

- 网关 `BankPayoutGateway`（SPI 在 `payouts`，HTTP 实现调模拟银行；连接/读超时可配，默认 5s/10s）：`submit(requestId, amount)`、`query(requestId)`。没有网关 bean 时不注册处理器（同 P3 T07）。
- `SUBMIT_PAYOUT` 执行：命令有过尝试（`attempts > 0`，即接管或重试）先 `query`；404 → 用同一 `requestId` 重新 `submit`（银行永久去重，不需要退款那条 20 小时规则）；有结果按结果写回。首次直接 `submit`。
- 写回（资金锁内，按命令 epoch 条件）：
  - `ACCEPTED` → payout `RESERVED/SUBMITTED/UNKNOWN → ACCEPTED`（新增直接边；`bank_ack_ref = paymentId`、`bank_execution_date`），命令 `DONE`。`EXECUTED` 同样只记 `ACCEPTED`（现金只认流水）。
  - `REJECTED` 且该 payout 没有已记账的出账流水（`bank_txn_id` 为空）→ `FAILED`、`failure_reason`、`PAYOUT_FAILED`（借 2104 / 贷 2102，`source_key=payout:{id}:failed`）、同事务 `RefundQueue.release()`，命令 `DONE`。已 `PAID` 时收到拒绝 → 不改 payout，开 `PAYOUT_STATE_CONFLICT`，命令 `DONE`。
  - 超时、断连、5xx、`TRANSPORT_ERROR` → payout `RESERVED/SUBMITTED → UNKNOWN`（已 `UNKNOWN` 不再记历史），命令 `UNKNOWN`、退避重试；达到上限停止自动重试，payout 保持 `UNKNOWN`（ADR-0008）。
  - 409 `REQUEST_CONFLICT`（不应出现）→ 命令 `DONE`、payout 不动、开 `PAYOUT_STATE_CONFLICT`。
- `POST /api/payouts/{id}/query`（运营接口，公开演示配置不注册）：payout 为 `UNKNOWN`/`ACCEPTED`/`SUBMITTED` 时建 `QUERY_PAYOUT`（同一 `request_id`，若已有未完成的同类命令则返回它），202；其他状态 409 `PAYOUT_NOT_QUERYABLE`。`QUERY_PAYOUT` 只查不发；404 → 若该 payout 的 `SUBMIT_PAYOUT` 已 `DONE`（银行曾确认受理）开 `PAYOUT_STATE_CONFLICT`，否则把 `SUBMIT_PAYOUT` 的 `next_attempt_at` 设为现在，交给它重发。

### 5. 银行导入里的提现证据（扩 P2/P4 银行匹配）

匹配顺序：先排除 PENDING、非 CAD（规则不变）；然后按 `reference` 前缀分流：`po_` 走批次规则，`mpo_` 走本节，`reference` 等于一条入账流水号走 P4 退还规则，其余走原规则。

- **出账（DEBIT）`reference = mpo_X`**：
  - X 存在、状态 ∈ `RESERVED/SUBMITTED/UNKNOWN/ACCEPTED`、金额相等、还没有出账流水 → `PAYOUT_PAID`（借 2104 / 贷 1103，`source_key=bank_txn:{bank_txn_id}`，occurred_at 取 booking_date 多伦多 00:00），payout → `PAID`、`bank_txn_id`、`paid_at`；流水 `PAYOUT_PAID`、`matched_payout_id = X`；以 X 为 subject 的 OPEN `PAYOUT_UNRESOLVED` 差异 → `RESOLVED`。仍在跑的 `SUBMIT_PAYOUT`/`QUERY_PAYOUT` 命令后续写回见到 `PAID` 只记尝试，不改 payout。
  - X 为 `FAILED` → 不记账，流水 `UNMATCHED_NO_POSTING`、`matched_payout_id = X`，开 `PAYOUT_STATE_CONFLICT`（subject = payout，证据含流水）（AC-7.11）。银行余额核对随后报 `BANK_BALANCE_MISMATCH`。修复走 ADR-0011 第 8 节纠错⑤。
  - X 已 `PAID`/`RETURNED`（第二笔出账）、或金额不等 → 不记账、`UNMATCHED_NO_POSTING`、开 `UNMATCHED_BANK_LINE`，候选 = X。
  - X 不存在 → 同上，无候选（AC-7.12）。
- **入账（CREDIT）`reference = mpo_X`**：
  - X 为 `PAID`、金额等于 X 的金额、还没有退回流水 → `PAYOUT_RETURNED`（借 1103 / 贷 2102，`source_key=bank_txn:{bank_txn_id}`），payout → `RETURNED`、`return_bank_txn_id`、`return_reason`（从 memo 里 `RETURN <CODE>` 取 CODE，取不到为 `UNKNOWN`）；流水 `PAYOUT_RETURNED`；同事务 `RefundQueue.release()`（AC-7.10）。
  - 其他情况（X 不是 `PAID`、金额不等、第二笔退回、X 不存在）→ 按现有入账规则记 `UNAPPLIED_CASH_RECEIVED`（钱确实进了账户），开 `UNMATCHED_BANK_LINE`，候选 = X；X 为 `FAILED` 时开 `PAYOUT_STATE_CONFLICT`。
- 同一 `bank_txn_id` 的重复证据（多个文件版本、重复导入）不重复记账（`source_key` 与流水身份；AC-7.13）。PENDING→BOOKED 后才匹配。

### 6. 对账与老化

- INV-9 补：2104 = Σ 状态 ∈ `RESERVED/SUBMITTED/UNKNOWN/ACCEPTED` 的 payout 金额。INV-11：1103 汇总加入 `PAYOUT_PAID`（贷，按出账 booking_date 归期）、`PAYOUT_RETURNED`（借，按退回 booking_date 归期）。
- BANK 段对账（AC-7.18）：每笔 `PAID` 的出账流水与 `RETURNED` 的退回流水在证据时点前的银行流水里存在且金额相等；对不上报 `PAYOUT_STATE_CONFLICT`。规则版本升到 `p5-v1`。
- AGING `PAYOUT_UNRESOLVED`：payout 处于 `SUBMITTED/UNKNOWN/ACCEPTED`、从第一次进入 `SUBMITTED` 或 `UNKNOWN` 或 `ACCEPTED` 的时刻（取最早，没有则 `reserved_at`）起超过 1 个工作日（contracts 1.3），且证据时点前没有出账流水。`RESERVED` 超过 1 个工作日也报（命令一直没发出去）。

### 7. 并发与崩溃

- 受理、银行导入、命令写回、退款受理都先取资金锁，所以样例 G、AC-7.14、AC-7.15 由资金锁串行化；测试用栅栏和两种受控交错证明。
- AC-7.16 的两个崩溃点用 `OutboundHooks`（抢占后 HTTP 前、HTTP 后写回前）注入；重启后接管者先查后发。

### 8. 页面

- 提现列表与详情页（状态时间线、银行请求号、每次尝试、出账/退回流水、分录）；总览加提现在途与 free_cash；提现表单（金额、业务编号）在模拟器模式可用。
- AC-7.19 演示脚本：给 PO1 配 `EXECUTE_DROP_RESPONSE`，提交、看到 `UNKNOWN`，worker 重试查询得到 `ACCEPTED`，推进银行时钟并导入流水后 `PAID`；页面显示银行请求号与流水行，`/sim/bank/payments` 只有一笔。

### 9. 明确不做

部分提现、取消已受理提现、多收款账户、提现手续费、即时到账、ACCEPTED 之后银行主动取消（没有查询就不知道；靠老化报警＋运营查询）。
