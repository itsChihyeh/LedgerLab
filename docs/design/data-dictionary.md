# 数据字典（初稿）

P0 设计稿 v0.1，2026-10-21。字段是设计层面的，P1 写 Flyway 迁移时以本文为准再细化；改动要回写本文。

约定：主键都是带前缀的 ULID 文本（见 [contracts.md](contracts.md)）；金额 `BIGINT` 分；时刻 `timestamptz`；所有表都有 `recorded_at`。“不可改”指 `app` 角色没有 UPDATE/DELETE 权限。

## 0. Schema 与角色

| schema | 内容 | `app` 角色 | `readonly` 角色 |
|---|---|---|---|
| `ledger` | 账户、分录、posting | 账户：SELECT＋UPDATE(仅余额列)；分录和 posting：INSERT/SELECT | SELECT 视图 |
| `core` | 支付、退款、提现、批次、状态历史 | INSERT/SELECT/UPDATE（状态列） | SELECT 视图 |
| `io` | inbox、出站命令、API 幂等记录、导入文件 | INSERT/SELECT/UPDATE | 不可见 |
| `recon` | 对账运行、差异、结算单行、银行流水行 | INSERT/SELECT/UPDATE（差异状态） | SELECT 视图 |
| `sim_processor`、`sim_bank` | 模拟器自己的状态和真值 | **不可见** | 不可见 |

模拟器用自己的数据库角色连自己的 schema（首版和主库同一个 PostgreSQL 实例，不同库更好但不是必需，见 ADR-0013）。`migrator` 拥有全部 DDL。`TRUNCATE` 对 `app` 一律不授予。

---

## 1. ledger

### `ledger.account`

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | `smallint` PK | 1101…2105，也是加锁顺序 |
| `name` | `text` | `processor_receivable` 等 |
| `kind` | `text` | `ASSET` / `LIABILITY` |
| `normal_side` | `char(1)` | `D` / `C` |
| `allow_negative` | `boolean` | 1101、2102 为 true |
| `balance` | `bigint` | 按正常方向计的当前余额，与 posting 同事务更新 |
| `version` | `bigint` | 每次记账 +1 |

`code`、`kind`、`normal_side`、`allow_negative` 由触发器禁止修改（复核 S4 的“修改账户属性”攻击）。

### `ledger.journal_entry`（不可改）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `text` PK | `je_…` |
| `seq` | `bigint` identity | 账本序号；调查接口返回“截至序号/时点”，避免多次查询拼出不存在于同一时刻的状态 |
| `entry_type` | `text` | money-model 第 3 节的 reason code |
| `source_key` | `text` UNIQUE | INV-8 |
| `posting_count` | `smallint` | ≥ 2；提交时核对（INV-4） |
| `total_amount` | `bigint` | Σ借 = Σ贷 = 此值 |
| `created_txid` | `xid8` | 默认 `pg_current_xact_id()`；posting 只能在同一事务插入 |
| `occurred_at` | `timestamptz` | 触发它的业务事实发生时刻 |
| `recorded_at` | `timestamptz` | |
| `subject_type`、`subject_id` | `text` | 关联的业务对象（`payment`/`refund`/`payout`/`batch`/`bank_txn`） |
| `reverses_entry_id` | `text` FK NULL | 只在 `REVERSAL` 上有值，UNIQUE（一个分录最多被冲销一次） |
| `correction_id` | `text` FK NULL | `REVERSAL` 和 `CORRECTION` 必填 |

### `ledger.posting`（不可改）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `text` PK | `pst_…` |
| `entry_id` | `text` FK | |
| `line_no` | `smallint` | (entry_id, line_no) UNIQUE |
| `account_code` | `smallint` FK | |
| `side` | `char(1)` | `D` / `C` |
| `amount` | `bigint` | CHECK > 0 |
| `account_version` | `bigint` | 记账后账户版本 |
| `balance_after` | `bigint` | 记账后账户余额（方便查历史余额、发现并发漏洞） |

### `ledger.correction`

| 字段 | 说明 |
|---|---|
| `id` `cor_…`、`original_entry_id`、`reason`（枚举＋自由文本）、`evidence_refs`（差异编号、结算单行、流水编号）、`requested_by`、`recorded_at` | 一次受限纠错 = 一条 correction ＋ 一个 `REVERSAL` ＋ 零或一个 `CORRECTION` 分录，同一事务 |

---

## 2. core

### `core.payment`

`id pay_…`、`processor_payment_ref` UNIQUE、`amount`（≥ 50）、`expected_fee`（按合同费率算）、`actual_fee`（批次给出后填）、`status`（`CAPTURED`/`SETTLED`）、`batch_id` NULL、`refunded_amount`（未失败、未取消的退款合计，CHECK ≤ amount）、`occurred_at`、`received_at`、`recorded_at`。

### `core.refund`

`id rfd_…`、`payment_id` FK、`merchant_refund_ref`（与 payment_id 联合 UNIQUE，永久）、`amount` > 0、`status`、`queue_seq`（受理顺序，资金队列用）、`processor_refund_ref` UNIQUE NULL、`failure_reason` NULL、`accepted_at`、`submitted_at`、`resolved_at`。

### `core.payout`

`id mpo_…`（同时是 `bank_request_id`）、`merchant_payout_ref` UNIQUE、`amount` > 0、`status`、`bank_ack_ref` NULL、`bank_txn_id` NULL（出账流水）、`return_bank_txn_id` NULL、`failure_reason` NULL、各状态时刻。

### `core.settlement_batch` 与 `core.settlement_batch_line`

批次：`batch_id`（Stripe payout 编号 `po_…`）PK、`settlement_date`、`gross`、`fees`、`refunds`、`payout_failures_returned`、`net`（> 0）、`status`、`hold_reason` NULL、`source_event_id`、`arrival_date`（处理方预计到账日）。CHECK `net = gross − fees − refunds + payout_failures_returned`。

批次行：`(batch_id, line_no)` PK、`line_type`（`PAYMENT`/`REFUND`/`PAYOUT_FAILURE`；其他 Stripe 类型如 `adjustment`、`dispute` 不支持 → 批次 `HELD`）、`processor_ref`、`balance_txn_id`（Stripe `txn_…`，UNIQUE）、`gross`、`fee`、`net`。

### `core.*_transition`

每类对象一张：`subject_id`、`from_status`、`to_status`、`evidence_type`、`evidence_id`、`occurred_at`、`recorded_at`。只追加。

---

## 3. io

### `io.inbox_event`

`event_id` PK（处理方给的）、`event_type`、`payload`（原始请求体，`bytea`）、`payload_sha256`、`signed_at`、`occurred_at`、`received_at`、`status`（`RECEIVED`/`PARKED`/`PROCESSED`/`REJECTED`/`DEAD`）、`attempts`、`lease_until`、`parked_on`（等待的对象键）、`last_error`。永久保留。

### `io.outbound_command`

`id cmd_…`、`kind`（`SUBMIT_REFUND`/`SUBMIT_PAYOUT`/`QUERY_REFUND`/`QUERY_PAYOUT`）、`subject_id`、`request_id`（对外幂等键，所有重试相同）、`status`（`PENDING`/`IN_FLIGHT`/`UNKNOWN`/`DONE`）、`attempts`、`next_attempt_at`、`lease_owner`、`lease_until`、`external_ref`、`last_outcome`、`created_at`。

### `io.processor_fetch`

处理事件时向处理方拉取的数据（例如 payout 的 balance transactions）：`id`、`event_id`、`request`（接口和参数）、`response_body`（原始 JSON）、`response_sha256`、`fetched_at`。只追加，作为批次行的原始证据。

### `io.idempotency_record`

`(client_id, idem_key)` PK、`request_fingerprint`（方法＋路径＋规范化请求体的哈希）、`state`（`IN_PROGRESS`/`COMPLETED`）、`response_status`、`response_body`、`locked_until`、`created_at`、`expires_at`（24 小时）。

### `io.imported_file`

`id`、`kind`（`PROCESSOR_STATEMENT`/`BANK_STATEMENT`）、`source_name`、`sha256` UNIQUE、`statement_date`、`version`、`received_at`。同一文件内容重复导入直接返回原导入结果。

---

## 4. recon

### `recon.statement_line`、`recon.bank_line`

结算单行：`file_id`、`statement_line_id`、`line_type`、`processor_ref`、`batch_id`、`gross`、`fee`、`net`、`occurred_at`、`settlement_date`、`memo`（自由文本，P7 用于提示注入测试）。

银行流水文件另记 `account_ref`、`currency`、`opening_balance`、`closing_balance`、`period_start`、`period_end`（INV-11）。

银行流水行：`bank_txn_id` UNIQUE、`file_id`、`direction`（`CREDIT`/`DEBIT`）、`amount`、`reference`（批次号或银行请求号）、`return_of` NULL（原流水或请求号）、`reason_code` NULL、`booking_date`、`value_date`、`memo`。

### `recon.run`

`id rr_…`、`kind`（`PROCESSOR`/`BANK`/`AGING`）、`input_file_ids`、`rule_version`、`cutoff_at`、`started_at`、`finished_at`、`summary`（各类差异数）。

### `recon.issue`

`id iss_…`、`category`、`subject_type`、`subject_id`（联合唯一键见状态机第 7 节）、`first_run_id`、`last_run_id`、`status`、`expected`、`actual`（JSON，金额和引用）、`evidence_refs`、`explanation` NULL、`resolution_correction_id` NULL。

差异类别（首版）：`MISSING_IN_LEDGER`、`MISSING_IN_STATEMENT`、`AMOUNT_MISMATCH`、`FEE_MISMATCH`、`DUPLICATE_STATEMENT_LINE`、`BATCH_TOTAL_MISMATCH`、`REFUND_NOT_IN_STATEMENT`、`DEPOSIT_MISSING`、`DEPOSIT_AMOUNT_MISMATCH`、`UNMATCHED_BANK_LINE`、`UNAPPLIED_CASH_OPEN`、`BANK_BALANCE_MISMATCH`、`BATCH_VERSION_CONFLICT`、`STATEMENT_VERSION_CONFLICT`、`TIMING_DIFFERENCE`、`PAYOUT_STATE_CONFLICT`，以及 contracts 1.3 节的老化类别。

---

## 5. 模拟器（独立 schema，主应用不可见）

- `sim_processor`（fake Stripe）：charge、refund、balance transaction、payout 的“真实”状态，字段命名跟 Stripe 一致；webhook 投递日志（每次尝试、是否故意重复/乱序/丢失/篡改）；结算单生成记录。
- `sim_bank`：账户余额、收到的付款请求（按 `bank_request_id` 去重）、流水行、退回计划、API 故障计划（超时、丢回执）。
- `sim_chaos`：chaos relay 每次转发、复制、延迟、乱序、丢弃、丢回执的日志（两种模式都用）。
- `sim_truth`：场景编号、种子、注入的故障清单和预期差异（ground truth），对账测试和 Agent 评测共用。

处理方和银行的状态分开存、分开推进，避免两边共享同一个错误（I6）。
