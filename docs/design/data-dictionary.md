# 数据字典（初稿）

P0 设计稿 v0.2，2026-10-21（同日开工前审查修订）。字段是设计层面的，P1 写 Flyway 迁移时以本文为准再细化；改动要回写本文。

约定：平台生成的实体主键是带前缀的 ULID 文本（见 [contracts.md](contracts.md)）；自然键、外部编号和联合主键按各表定义（例如账户用 `smallint` 代码、inbox 用处理方事件号）。金额 `BIGINT` 分；时刻 `timestamptz`；所有表都有 `recorded_at`。“不可改”指 `app` 角色没有 UPDATE/DELETE 权限。

## 0. Schema 与角色

| schema | 内容 | `app` 角色 | `readonly` 角色 |
|---|---|---|---|
| `ledger` | 资金锁、账户、分录、posting、纠错 | 见下表，按列授权 | SELECT 视图 |
| `core` | 支付、退款、提现、批次、状态历史 | 见下表，按列授权 | SELECT 视图 |
| `io` | inbox、出站命令、API 幂等记录、导入文件、拉取存档 | INSERT/SELECT，UPDATE 只给下表列出的列 | 不可见 |
| `recon` | 对账运行、差异、结算单行、银行流水行 | INSERT/SELECT，UPDATE 只给差异的处置列 | SELECT 视图 |
| `sim_processor`、`sim_bank`、`sim_chaos`、`sim_truth` | 模拟器自己的状态和真值 | **不可见** | 不可见 |

模拟器用自己的数据库角色连自己的 schema（首版和主库同一个 PostgreSQL 实例，不同库更好但不是必需，见 ADR-0013）。`migrator` 拥有全部 DDL 并且是表的属主。`TRUNCATE` 对 `app` 一律不授予。授权一律**按列列出**（`GRANT UPDATE (col, …)`），不先授整表再撤销。

P1a 落地方式（2026-10-21）：角色是集群级对象，不放在 Flyway 里，由数据库初始化脚本 `docker/postgres/initdb/01-roles.sh` 创建（Docker Compose 和 Testcontainers 共用同一个脚本）：`migrator`、`app`、`readonly`，以及模拟器用的 `simulator`（拥有四个 `sim_*` schema，P1a 时为空）。数据库对 `PUBLIC` 收回 CONNECT 和 TEMP，`public` schema 对 `PUBLIC` 收回全部权限。Flyway 以 `migrator` 连接，历史表放在单独的 `flyway` schema，`app` 和 `readonly` 都看不到。

### 0.1 P1 表的写入契约

P1 的第一个迁移照这张表写授权、约束和测试。P2 以后新增的表在各自的迁移里按同样格式补进来。

| 表 | `app` 可写 | 数据库强制 | 直连 SQL 验收（以 `app` 角色） |
|---|---|---|---|
| `ledger.funds_lock` | UPDATE(`touched_at`)（只为加行锁） | 只有一行，CHECK `id = 1` | INSERT、DELETE 被拒（42501） |
| `ledger.account` | UPDATE(`balance`, `version`) | 余额 CHECK（见第 1 节）；属性列不在授权里；属性改动另由触发器拒绝（防属主误操作） | 改属性 42501；余额改成 −1（不可为负的账户）被 CHECK 拒绝（23514）；余额改成合法但错误的值能执行，INV-2 检查报错 |
| `ledger.journal_entry`、`ledger.posting` | 只 INSERT/SELECT | 封存触发器（第 1 节）；`created_txid` 由触发器写入 | UPDATE、DELETE、TRUNCATE 42501；追加攻击、空分录、单边、条数不符在提交时失败 |
| `core.payment` | INSERT；UPDATE(`status`, `actual_fee`, `batch_id`, `refunded_amount`) | 状态 CHECK；`refunded_amount ≤ amount`；金额范围 CHECK | 改 `amount`、`processor_payment_ref` 42501 |
| `core.payment_transition` | 只 INSERT/SELECT | — | UPDATE、DELETE 42501 |
| `io.inbox_event` | INSERT；UPDATE(`status`, `result`, `attempts`, `next_attempt_at`, `lease_owner`, `lease_until`, `lease_epoch`, `parked_on`, `last_error`, `processed_at`) | `payload`、`payload_sha256`、`event_id` 不在授权里 | 改 `payload` 42501 |
| `io.inbox_conflict` | 只 INSERT/SELECT | — | UPDATE、DELETE 42501 |
| `recon.issue` | INSERT；UPDATE(`status`, `last_run_id`, `explanation`, `resolution_correction_id`, `updated_at`) | — | 改 `category`、`expected`、`actual` 42501 |
| 只读视图 | — | `readonly` 只能 SELECT 视图，底表、`io`、`sim_*` 不可见 | 以 `readonly` 读底表、`io`、`sim_*` 被拒；读视图成功；任何写入被拒 |

纠错（P4）会改到支付金额等目前不在授权里的列：怎么改由 P4 开工前补的纠错类型定义决定（ADR-0011 补充），到时新增迁移扩授权，并同步本表。

---

## 1. ledger

### `ledger.funds_lock`

只有一行（`id = 1`，`touched_at`）。每个会动钱的事务**第一步**对它 `SELECT … FOR UPDATE`，然后才锁业务行和账户（ADR-0004 补充）。单商户下资金写入本来就在 2102 上串行，这把锁不降低吞吐，但让整个事务的加锁顺序固定，避免跨多次记账调用的死锁。

### `ledger.account`

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | `smallint` PK | 1101…2105，也是加锁顺序 |
| `name` | `text` | `processor_receivable` 等 |
| `kind` | `text` | `ASSET` / `LIABILITY` |
| `normal_side` | `char(1)` | `D` / `C` |
| `allow_negative` | `boolean` | 1101、2102 为 true |
| `balance` | `bigint` | 按正常方向计的当前余额，与 posting 同事务更新 |
| `version` | `bigint` | 每次记账 +1，和 `balance` 在同一条 UPDATE 里改 |

约束：CHECK `allow_negative OR balance >= 0`（INV-5 在数据库层兜底）；CHECK `abs(balance) < 9007199254740992`（汇总值上限，contracts 第 3 节）。`code`、`kind`、`normal_side`、`allow_negative` 不在 `app` 的授权里，另由触发器禁止任何角色修改（复核 S4 的“修改账户属性”攻击）。

### `ledger.journal_entry`（不可改）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `text` PK | `je_…` |
| `seq` | `bigint` identity | 账本序号；调查接口返回“截至序号/时点”，避免多次查询拼出不存在于同一时刻的状态 |
| `entry_type` | `text` | money-model 第 3 节的 reason code |
| `source_key` | `text` UNIQUE | INV-8 |
| `posting_count` | `smallint` NOT NULL | CHECK ≥ 2；提交时核对（INV-4）；批次分录按实际条数（2～5） |
| `total_amount` | `bigint` NOT NULL | CHECK > 0；Σ借 = Σ贷 = 此值 |
| `created_txid` | `xid8` NOT NULL | 由 BEFORE INSERT 触发器写成 `pg_current_xact_id()`，覆盖调用方传入的任何值；posting 只能在同一事务插入 |
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

封存的触发器（ADR-0003）：

1. posting 的 BEFORE INSERT 触发器：父分录的 `created_txid` 必须等于当前事务号，否则拒绝。
2. **分录头和 posting 各有一个** `AFTER INSERT` 的可延迟约束触发器（`DEFERRABLE INITIALLY DEFERRED`），都调用同一个检查：该分录实际 posting 条数 = `posting_count`，Σ借 = Σ贷 = `total_amount`。分录头上也挂，是为了没有任何 posting 的空分录也会被检查；`SUM` 对空集合返回 NULL，检查里按 0 处理。
3. 调用方提前 `SET CONSTRAINTS ALL IMMEDIATE` 时，之后插入的 posting 在语句结束时就检查条数和合计，一次插入一对平衡的 posting 也会因条数不符被拒绝。
4. posting 金额 CHECK > 0，`side`、`amount`、`account_code` NOT NULL。

### P1a 实现备注（V1 迁移）

- 只读视图：`ledger.v_account`、`ledger.v_journal_entry`、`ledger.v_posting`（不含 `created_txid`），`readonly` 只有这三个视图的 SELECT。
- `account.version` 按 posting 计：每条 posting 让所在账户版本 +1（同一分录两条 posting 记到同一账户就 +2），`posting.account_version` 和 `balance_after` 是该条 posting 之后的值。INV-2 的检查同时核对余额和版本（版本 = 该账户的 posting 条数）。
- 触发器的自定义 SQLSTATE：`LL001` 账户属性不可改；`LL002` 向别的事务创建的分录追加 posting；`LL003` 提交时条数或借贷合计与分录头不符；`LL004` 改、删、清空分录或 posting。`LL004` 是额外加的一层：`app` 本来就没有这些权限（得到 42501），它拦的是属主 `migrator` 的误操作。
- `journal_entry` 另有两条 CHECK：只有 `REVERSAL` 带 `reverses_entry_id`；`REVERSAL` 和 `CORRECTION` 必须带 `correction_id`。`correction_id` 指向 `ledger.correction` 的外键在 P4 建表时补上。
- 记账接口 `Ledger.post` 只在调用方的事务里工作，并要求同一事务先调用 `Ledger.lockFunds()`；账户按代码升序逐个 `FOR UPDATE`。

### `ledger.correction`

| 字段 | 说明 |
|---|---|
| `id` `cor_…`、`original_entry_id`、`reason`（枚举＋自由文本）、`evidence_refs`（差异编号、结算单行、流水编号）、`requested_by`、`recorded_at` | 一次受限纠错 = 一条 correction ＋ 一个 `REVERSAL` ＋ 零或一个 `CORRECTION` 分录，同一事务 |

---

## 2. core

### `core.payment`

`id pay_…`、`processor_payment_ref` UNIQUE、`processor_intent_ref`（`pi_…`）、`amount`（50～99999999）、`expected_fee`（按合同费率算）、`actual_fee`（批次给出后填）、`status`（`CAPTURED`/`SETTLED`/`VOIDED`）、`batch_id` NULL、`refunded_amount`（未失败、未取消的退款合计，CHECK ≤ amount）、`source_event_id`、`occurred_at`、`received_at`、`recorded_at`。

### `core.refund`

`id rfd_…`、`payment_id` FK、`merchant_refund_ref`（与 payment_id 联合 UNIQUE，永久）、`amount` > 0、`status`、`queue_seq`（受理顺序，资金队列用）、`processor_refund_ref` UNIQUE NULL、`failure_reason` NULL、`initial_result`（第一次受理时返回的状态码和响应体，永久保存，用于业务编号重放）、`request_fingerprint`、`accepted_at`、`submitted_at`、`resolved_at`。

### `core.payout`

`id mpo_…`（同时是 `bank_request_id`）、`merchant_payout_ref` UNIQUE、`amount` > 0、`status`、`initial_result`、`request_fingerprint`、`bank_ack_ref` NULL、`bank_txn_id` NULL（出账流水）、`return_bank_txn_id` NULL、`failure_reason` NULL、各状态时刻。

### `core.settlement_batch` 与 `core.settlement_batch_line`

批次：`batch_id`（Stripe payout 编号 `po_…`）PK、`payout_amount`（payout 对象上的金额）、`settlement_date`、`gross`、`fees`、`refunds`、`payout_failures_returned`、`net`（> 0，且 = `payout_amount`）、`status`（`ANNOUNCED`/`RECEIVED`/`CONFIRMED`/`HELD`/`PAYOUT_FAILED`/`BANKED`）、`hold_reason` NULL（`UNKNOWN_PAYMENT`、`AMOUNT_MISMATCH`、`UNSUPPORTED_LINE`、`UNSUPPORTED_PAYOUT`、`FEE_EXCEEDS_GROSS` 等）、`source_event_id`、`lines_fetch_id` NULL（就绪且拉完的那次 `io.processor_fetch`）、`arrival_date`（处理方预计到账日）。CHECK `net = gross − fees − refunds + payout_failures_returned`。

批次行：`(batch_id, line_no)` PK、`line_type`（`PAYMENT`/`REFUND`/`PAYOUT_FAILURE`；其他 Stripe 类型如 `adjustment`、`dispute` 不支持 → 批次 `HELD`）、`processor_ref`、`balance_txn_id`（Stripe `txn_…`，UNIQUE）、`gross`、`fee`、`net`。

### `core.*_transition`

每类对象一张：`subject_id`、`from_status`、`to_status`、`evidence_type`、`evidence_id`、`occurred_at`、`recorded_at`。只追加。

### P1b-1 实现备注（V2 迁移）

- `payment.id` 为 `pay_` 加 26 位 ULID，沿用 V1 的字符集检查；`processor_payment_ref` 必须以 `ch_` 开头且有后缀。`processor_intent_ref`、`actual_fee`、`batch_id` 可空，其余字段非空；手续费非负，`refunded_amount` 默认 0，范围为 0～`amount`。
- `batch_id` 到批次表的外键等 P2 补上；`source_event_id` 到 `io.inbox_event` 的外键等该表创建时补上。
- `payment_transition.id` 为 `bigint GENERATED ALWAYS AS IDENTITY` 主键；`subject_id` 外键指向 `payment.id`。`from_status` 可空（首次进入 CAPTURED），其他字段非空；两个状态列的非空值均限于 `CAPTURED`、`SETTLED`、`VOIDED`。
- 状态历史的 UPDATE、DELETE、TRUNCATE 由触发器拒绝（`LL004`），也防属主误操作。在 `core` 内定义拒绝函数，避免依赖 ledger 专用函数及其分录纠错错误消息。
- `app` 可 SELECT、INSERT 两张表，只有 payment 的 `status`、`actual_fee`、`batch_id`、`refunded_amount` 可 UPDATE。`readonly` 仅可 SELECT `core.v_payment`、`core.v_payment_transition`，视图显式列出各表全部字段。

---

## 3. io

### `io.inbox_event`

`event_id` PK（处理方给的）、`event_type`、`api_version`、`livemode`、`payload`（原始请求体，`bytea`）、`payload_sha256`、`signed_at`、`occurred_at`、`received_at`、`status`（`RECEIVED`/`PARKED`/`PROCESSED`/`REJECTED`/`IGNORED`/`DEAD`）、`result` NULL（`APPLIED`/`DUPLICATE_FACT`/`BUSINESS_CONFLICT`/`INVALID_AMOUNT`/`UNSUPPORTED_CURRENCY`/`UNSUPPORTED_CAPTURE`/`UNSUPPORTED_TYPE`/`LIVEMODE`）、`attempts`、`next_attempt_at`、`lease_owner`、`lease_until`、`lease_epoch`（每次抢占 +1，写回时作条件，见 state-machines 第 5 节）、`parked_on`（等待的对象键）、`last_error`、`processed_at`。永久保留。

### `io.inbox_conflict`

同一 `event_id` 不同内容的投递：`id`、`event_id`、`payload`、`payload_sha256`、`signed_at`、`received_at`。只追加；原事件行不动（AC-2.4）。

### `io.outbound_command`

`id cmd_…`、`kind`（`SUBMIT_REFUND`/`SUBMIT_PAYOUT`/`QUERY_REFUND`/`QUERY_PAYOUT`）、`subject_id`、`request_id`（对外幂等键，所有重试相同）、`status`（`PENDING`/`IN_FLIGHT`/`UNKNOWN`/`DONE`）、`attempts`、`next_attempt_at`、`lease_owner`、`lease_until`、`lease_epoch`、`first_sent_at`（第一次真正发出的时刻，写入后不改）、`external_ref`、`last_outcome`、`created_at`。

### `io.outbound_attempt`

每次外部调用一行：`command_id`、`attempt_no`、`lease_epoch`、`sent_at`、`request_summary`、`response_status`、`response_body`、`error`、`finished_at`。只追加；旧租约迟到的回执也记在这里，但不改业务对象。

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

`id iss_…`、`category`、`subject_type`、`subject_id`（联合唯一键见状态机第 7 节）、`detected_by`（`RUN`/`INGESTION`/`AGING`；P1 的入站冲突用 `INGESTION`，不需要对账运行）、`first_run_id` NULL、`last_run_id` NULL、`status`、`expected`、`actual`（JSON，金额和引用）、`evidence_refs`、`explanation` NULL、`resolution_correction_id` NULL、`updated_at`。

差异类别（首版）：`MISSING_IN_LEDGER`、`MISSING_IN_STATEMENT`、`AMOUNT_MISMATCH`、`FEE_MISMATCH`、`DUPLICATE_STATEMENT_LINE`、`BATCH_TOTAL_MISMATCH`、`REFUND_NOT_IN_STATEMENT`、`DEPOSIT_MISSING`、`DEPOSIT_AMOUNT_MISMATCH`、`UNMATCHED_BANK_LINE`、`UNAPPLIED_CASH_OPEN`、`BANK_BALANCE_MISMATCH`、`BATCH_VERSION_CONFLICT`、`STATEMENT_VERSION_CONFLICT`、`TIMING_DIFFERENCE`、`PAYOUT_STATE_CONFLICT`、`BATCH_STATE_CONFLICT`、`REFUND_STATE_CONFLICT`、`UNSUPPORTED_PAYOUT`，入站类 `EVENT_CONTENT_CONFLICT`、`EVENT_BUSINESS_CONFLICT`、`UNSUPPORTED_EVENT`、`LIVEMODE_EVENT`，以及 contracts 1.3 节的老化类别。

---

## 5. 模拟器（独立 schema，主应用不可见）

- `sim_processor`（fake Stripe）：charge、refund、balance transaction、payout 的“真实”状态，字段命名跟 Stripe 一致；webhook 投递日志（每次尝试、是否故意重复/乱序/丢失/篡改）；结算单生成记录。
- `sim_bank`：账户余额、收到的付款请求（按 `bank_request_id` 去重）、流水行、退回计划、API 故障计划（超时、丢回执）。
- `sim_chaos`：chaos relay 每次转发、复制、延迟、乱序、丢弃、丢回执的日志（两种模式都用）。
- `sim_truth`：场景编号、种子、注入的故障清单和预期差异（ground truth），对账测试和 Agent 评测共用。

处理方和银行的状态分开存、分开推进，避免两边共享同一个错误（I6）。
