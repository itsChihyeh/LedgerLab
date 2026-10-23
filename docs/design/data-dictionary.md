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
| `io.webhook_delivery` | 只 INSERT/SELECT | 投递结果、哈希格式、事件外键；UPDATE/DELETE/TRUNCATE 触发器（LL004） | 改、删、清空 42501；属主操作 LL004；readonly 只读 core 摘要视图 |
| `recon.issue` | INSERT；UPDATE(`status`, `last_run_id`, `explanation`, `resolution_correction_id`, `updated_at`, `fingerprint`, `reopen_count`, `expected`, `actual`, `evidence_refs`)；内容三列只能由对账运行按最新发现刷新 | — | 改 `category`、`subject_type`、`subject_id` 42501 |
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

### P2 T11 实现备注（V11 迁移）

- `core.batch_transition` 增加可空的 `hold_reason text` 和 `lines_fetch_id bigint REFERENCES io.processor_fetch(id)`；在唯一的批次历史写入点保存当时原因与固定明细存档。旧历史不回填；`to_status <> 'HELD' OR hold_reason IS NOT NULL` 用 `NOT VALID` 保留旧行，对新写入强制检查。
- `core.v_batch_transition` 追加这两列；migrator 仍为属主，app 沿用 SELECT/INSERT、无 UPDATE/DELETE/TRUNCATE，readonly 只读视图。没有新增表或扩大写权限。
- T11 按证据时点前最后一条历史恢复状态和原因；旧 HELD 历史缺原因或存档时不猜当前值，报告列出该覆盖边界。

### P2 T02 实现备注（V6 迁移）

- `settlement_batch` 补充不可改的 `currency text`、`automatic boolean`、`method text`、`occurred_at timestamptz`（payout created）、`recorded_at timestamptz`，以及可改的 `updated_at timestamptz`，均非空。`batch_id` 必须以 `po_` 开头且有后缀；`source_event_id` 外键指向 inbox。`settlement_date`、`arrival_date`、`lines_fetch_id` 和五项合计可空，保留尚未就绪的批次；`lines_fetch_id` 外键指向 `io.processor_fetch.id`。
- `payout_amount` 和 `net` 的范围为 −9007199254740991～9007199254740991 分，其余四项合计为 0～9007199254740991 分。原始 payout 金额允许为负或零，币种、automatic、method 不在表上强制支持范围，以便存下 `HELD(UNSUPPORTED_PAYOUT)` 的证据。合计齐全时必须满足净额公式；`CONFIRMED`、`BANKED`、`PAYOUT_FAILED` 必须五项合计齐全、`net > 0` 且等于 `payout_amount`。这些表约束不替代后续业务准入及逐行核对。
- `HELD` 当且仅当 `hold_reason` 非空；原因枚举为 `UNKNOWN_PAYMENT`、`AMOUNT_MISMATCH`、`UNSUPPORTED_LINE`、`UNSUPPORTED_PAYOUT`、`FEE_EXCEEDS_GROSS`、`LINE_IN_OTHER_BATCH`、`TOTAL_MISMATCH`、`LINES_NOT_READY`。
- 批次行另有非空 `occurred_at`（balance transaction created）和 `recorded_at`；`line_no integer` 从 1 起，`batch_id` 是外键。金额保留处理方方向：`PAYMENT`、`PAYOUT_FAILURE` 的 `gross` 为 1～99999999，`REFUND` 的 `gross` 为 −99999999～−1；`fee` 为 0～99999999，`net` 为 −99999999～99999999，并满足 `net = gross - fee`。`balance_txn_id` 必须以 `txn_` 开头且有后缀、跨批次唯一。插入触发器锁住批次行并检查其状态为 `CONFIRMED`（否则 `23514`），保证未核对通过的明细仅留在 fetch 原文中；行插入后可随批次进入 `BANKED` 或 `PAYOUT_FAILED`。
- `batch_transition` 与 `payment_transition` 同形：`id bigint GENERATED ALWAYS AS IDENTITY` 主键，`subject_id` 外键指向批次；`from_status` 可空，其他字段非空，两状态列均检查批次状态枚举。
- `io.processor_fetch.id` 采用 `bigint GENERATED ALWAYS AS IDENTITY`，`event_id` 可空且外键指向 inbox；其余字段非空：`purpose`（`BATCH_LINES` / `PAYOUT` / `RECON_SNAPSHOT`）、`request jsonb`、`response_body text`、`response_sha256 text`、`complete boolean`、`fetched_at timestamptz`、`recorded_at timestamptz`。多页原文存为数组文本，SHA-256 对保存文本的 UTF-8 字节计算，并用 CHECK 验证；不把原文转为 jsonb 后再算哈希。未拉完的响应也可存档，`complete=false`。
- 批次行、状态历史、fetch 的 UPDATE、DELETE、TRUNCATE 均由触发器拒绝（`LL004`），包括属主误操作。补齐 V2 留下的 `payment.batch_id` 外键。
- 四张表均由 `migrator` 持有；`app` 只有 INSERT/SELECT，批次额外仅可 UPDATE `status`、`hold_reason`、`settlement_date`、`arrival_date`、五项合计、`lines_fetch_id`、`updated_at`。`readonly` 仅可 SELECT 显式列出全部字段的 `core.v_settlement_batch`、`core.v_settlement_batch_line`、`core.v_batch_transition`，不能访问底表或 `io`。

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

P1b-4 入口自行按 Stripe 协议验证原始字节的 HMAC-SHA256，检查签名时间 ±300 秒；配置为 `ledgerlab.stripe.webhook-secret`（`STRIPE_WEBHOOK_SECRET`，必填）及 `ledgerlab.stripe.api-key`（`STRIPE_API_KEY`，P1b 可空、仅允许测试密钥）。签名有效但 JSON 不合法或缺必需的事件元数据时返回 400、不入库，因为无法构成有效的 inbox 记录。

`event_id` PK（处理方给的）、`event_type`、`api_version`、`livemode`、`payload`（原始请求体，`bytea`）、`payload_sha256`、`signed_at`、`occurred_at`、`received_at`、`status`（`RECEIVED`/`PARKED`/`PROCESSED`/`REJECTED`/`IGNORED`/`DEAD`）、`result` NULL（`APPLIED`/`DUPLICATE_FACT`/`BUSINESS_CONFLICT`/`INVALID_AMOUNT`/`UNSUPPORTED_CURRENCY`/`UNSUPPORTED_CAPTURE`/`UNSUPPORTED_TYPE`/`LIVEMODE`）、`attempts`、`next_attempt_at`、`lease_owner`、`lease_until`、`lease_epoch`（每次抢占 +1，写回时作条件，见 state-machines 第 5 节）、`parked_on`（等待的对象键）、`last_error`、`processed_at`。永久保留。

### `io.inbox_conflict`

同一 `event_id` 不同内容的投递：`id`、`event_id`、`payload`、`payload_sha256`、`signed_at`、`received_at`。只追加；原事件行不动（AC-2.4）。

P1b-2 落地（V3）：两表均有 NOT NULL 的 `recorded_at timestamptz`。事件的 `event_id` 为 text 主键，CHECK 以 `evt_` 开头；`event_type`、`api_version` 为 text，`livemode` 为 boolean，原始内容为 bytea，哈希为 text，四个事件时刻均为 timestamptz，均 NOT NULL。两表用 CHECK 保证 `payload_sha256 = encode(sha256(payload), 'hex')`。`livemode=true` 允许入库，后续处理标为 `REJECTED/LIVEMODE`。

事件 `status` NOT NULL，`result` 可空，取值限于上列枚举：`PROCESSED` 必须配 `APPLIED` 或 `DUPLICATE_FACT`；`REJECTED` 必须有其他结果；其余状态结果必须为 NULL。`attempts int`、`lease_epoch bigint` 均 NOT NULL、默认 0 且非负；`next_attempt_at timestamptz` NOT NULL；`lease_owner text`、`lease_until timestamptz`、`parked_on text`、`last_error text`、`processed_at timestamptz` 可空。`PARKED` 必须填写 `parked_on`，其他状态必须为空。抢占索引为 `(status, next_attempt_at, received_at)`。

冲突表所有列 NOT NULL；`id bigint GENERATED ALWAYS AS IDENTITY` 为主键，`event_id` 外键指向事件表，其余字段类型与事件表一致。UPDATE/DELETE/TRUNCATE 触发器以 `LL004` 拒绝改写（包括属主）。`core.payment.source_event_id` 同时补上到事件表的外键。

`app` 对两表有 SELECT/INSERT；只对事件表的 `status, result, attempts, next_attempt_at, lease_owner, lease_until, lease_epoch, parked_on, last_error, processed_at` 按列授 UPDATE，不授 DELETE/TRUNCATE。`readonly` 无 io schema 或表权限；P1c-3 仅通过下述 core 视图读取投递摘要。

### `io.webhook_delivery`（P1c-3，V5）

每次通过验签且元数据有效、可入 inbox 的投递记一行，与接收事务一起提交；同内容重投不增加 inbox 行数，但增加投递记录。签名无效、JSON 或必需元数据无效的请求仍不落库。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `bigint GENERATED ALWAYS AS IDENTITY` PK | 投递序号，相同时刻按此排序 |
| `event_id` | `text` NOT NULL FK | 指向 `io.inbox_event.event_id` |
| `outcome` | `text` NOT NULL | CHECK：`NEW`、`DUPLICATE`、`CONFLICT` |
| `payload_sha256` | `text` NOT NULL | 本次投递哈希，CHECK 64 位十六进制 |
| `signed_at`、`received_at`、`recorded_at` | `timestamptz` NOT NULL | 本次签名、收到、记录时刻 |

只追加，触发器禁止 UPDATE/DELETE/TRUNCATE（LL004，包括属主）；app 仅 SELECT/INSERT，readonly 无底表权限。

`core.v_payment_delivery` 由 migrator 拥有，用属主权限读取 io；仅暴露 `id`、`event_id`、`event_type`、投递 `outcome`、`received_at`、事件当前 `status`/`result`，以及原事件 `data.object.id` 提取的 `processor_payment_ref`（仅 `charge.*`）。不暴露原文、哈希或冲突原文，readonly 仅 SELECT 视图，仍无 io 权限。CONFLICT 归于原事件的 charge。

### P1c-3 只读支付查询契约

investigation 使用限定名 `investigationDataSource`，配置 `ledgerlab.readonly-datasource.url/username/password`，默认 URL 同主数据源、用户名 readonly、密码来自 `LEDGERLAB_READONLY_PASSWORD`。额外数据源不作为默认候选；不替换 app 数据源、事务管理器或 Flyway 连接。

- `GET /api/payments/{ref}` 支持本地 `pay_…` 和处理方 `ch_…`，不存在返回 404。JSON 字段：`id`、`processorPaymentRef`、`amount`（整数分）、`currency`（CAD）、`status`、`expectedFee`、`postings`、`deliveries`、`asOf`。
- postings 包含 `entryId`、`entryType`、`lineNo`、`accountCode`、`accountName`、`side`、`amount`，按分录 seq、行号排序。deliveries 包含 `eventId`、`eventType`、`outcome`、`status`、`result`（可空）、`receivedAt`，按 received_at、id 排序；事件状态和结果是读取快照时的当前值。
- `GET /api/payments?limit=n` 返回 `{payments: [...], asOf: {...}}`；摘要为上述支付基本字段，无 postings/deliveries。按 recorded_at 降序、id 降序；limit 默认 20，仅接受 1～100，非整数或越界返回 400。
- 每次接口的全部查询都在独立 `REPEATABLE READ` 只读事务中，且只访问视图。`asOf.seq` 是该快照可见的最大账本序号（空账本为 0），`asOf.at` 是取得快照的数据库语句时刻；它不是业务截止时间，也不意味着序号连续或不存在尚未提交的较小序号。

### P2 T13 实现备注：只读查询与运营处置

沿用 P1c-3 的数据源与事务契约：每个新增 GET 及支付详情使用 `investigationDataSource` / `readonly`，全部查询处于一个独立 `REPEATABLE READ` 只读事务。先查询可见最大账本序号建立快照，再从注入 Clock 取 `asOf.at`（T13 起替代上述数据库语句时刻，统一时钟来源）。该时刻是观察标签，不是过滤条件；历史重放仍用 run 的 cutoff/evidence 时点。现有 V1–V11 视图已覆盖所需字段，无新增迁移或授权；不直接读取 io，不返回原始 webhook／处理方响应。

- `GET /api/overview` 返回 `accounts[{code,name,balance}]`（八个账户，代码升序）、`inTransit`（1102）、`bankCash`（1103）、`merchantAvailable`（2102 原始有符号余额）、`merchantOwes`（余额负时精确取反，否则 0）、`unappliedCash`（2105）、`openIssuesByCategory`、`batchesByStatus`、`asOf`。计数对象仅含非零类别／状态，缺项视为 0。
- 支付详情新增可空 `actualFee`、`net`（有实际手续费时 amount − actualFee，未结算为 null）、`batch{batchId,status,settlementDate,arrivalDate}`、`bankDeposit{bankTxnId,amount,bookingDate}`。入账只取 matched_batch_id 的实际匹配行，候选不算入账。posting 范围为**支付自己的分录 + 当前所属整个批次的分录 + 该批次匹配银行流水的分录**，按分录 seq／行号排序，每条只出现一次；批次金额不分摊到单笔支付。S3 为 2 + 5 + 2 = 9 条；多支付批次仍返回整批 posting。
- `GET /api/batches?limit=` 返回 `{batches,asOf}`；`GET /api/batches/{id}` 返回 `{batch,lines,history,entries,postings,bankLines,asOf}`。批次包含既有视图业务字段，未就绪合计／日期保持 null；行含 `balanceTxnId`。history 按自增 id 升序；entries/postings 含本批次及实际匹配银行流水分录，bankLines 只含实际匹配行。
- `GET /api/recon/issues?status=&category=&limit=` 返回 `{issues,asOf}`，保留当前 expected/actual/evidenceRefs JSON；按 updated_at、id 降序。status 只接受 OPEN/EXPLAINED/ACCEPTED/RESOLVED，否则 400；category 精确匹配，未知类别返回空集。省略筛选即全部。
- `GET /api/recon/issues/{id}` 返回 `{issue,amounts,history,findings,references,asOf}`。amounts 逐项提供 `{metric,statementLineId,expectedAmount,actualAmount,difference}`，difference = expected − actual，精确运算且检查 JSON 安全整数范围。只比较两边存在的金额字段（amount/gross/fee/net/unapplied；银行 closing_balance 对 cash；候选批次 net 对流水 amount），展开实际金额数组及 fees/lines、入口差异的嵌套 line；批次 payout_amount 分别对实际 payout_amount／line_net，max_fee 对实际 fee；存在性、数量或缺失值不伪装为零金额。保留原始 JSON；findings 按运行 started_at、run_id、finding id 排序，全部返回；history 按 recorded_at、id 排序。旧发现不随当前处置改写。
- references 返回 `batchIds`、`statementLines[{fileId,lineSeq,statementLineId}]`、`eventIds`、`entryIds`、`bankTxnIds`、`paymentIds`。合并当前差异、全部历史发现／处置证据中的明确引用，并补关联支付、批次、结算单版本及银行行；编号排序去重并去掉空白引用，结算单保留各文件行身份。银行候选引用可用于导航，但不等于匹配；仅 payment.bankDeposit 和 batch.bankLines 表示匹配。事件仅返回编号；相关 posting 可由支付、批次或银行详情查询。
- `GET /api/recon/runs?limit=` 返回 `{runs,asOf}`，含固定输入、哈希、规则版本、两个时点及 summary JSON，按 started_at、id 降序。原有 T09 的运行详情／发起运行接口保持原契约。
- `GET /api/bank-lines/{bankTxnId}` 返回 `{bankLine,versions,entries,postings,asOf}`，bankLine 为最高证据版本，versions 按 version 降序；分录按 bank_txn 身份查。`GET /api/statement-lines/{fileId}/{seq}` 返回 `{statementLine,asOf}`，精确定位文件内行，不混淆版本或重复行；seq 必须为正整数。
- 所有列表 limit 默认 20，范围 1–100；非整数或越界 400。批次列表按 recorded_at、batch_id 降序。详情未知编号 404。前端类型正本为 `frontend/src/api.ts`。
- `POST /api/recon/issues/{id}/accept` 接受 `{reason}`，`/resolve` 接受 `{note}`，在 reconciliation 委托既有 IssueDisposition，走 app 数据源。成功 204；缺失/null/空白说明 400，未知差异 404，非 OPEN 或并发状态冲突 409。状态与 OPERATOR 历史同事务写入，不改账或历史发现。

### P2 T13b 实现备注（V12：批次投递与事件摘要）

- 新增 migrator 拥有的 `core.v_batch_delivery`，以属主权限读取 io，列为 `id,event_id,event_type,outcome,received_at,status,result,batch_id`；仅匹配 `payout.*`，批次号从原事件 `data.object.id` 提取。CONFLICT 仍归于原事件对象，不读取冲突原文。
- 新增 `core.v_event`（`event_id,event_type,api_version,livemode,occurred_at,received_at,status,result,subject,subject_id`）及 `core.v_event_delivery`（投递七字段加 `subject`）。`subject` 为 charge.* 的 `payment` 或 payout.* 的 `batch`；其他类型为 null。`subject_id` 从原事件对象提取，是处理方支付号或批次号，不要求本地业务对象已创建；不支持的类型或缺失对象编号为 null。P2 不推断退款等其他关联。
- 三个视图均仅向 readonly 授 SELECT，app 无新增权限，readonly 仍无 io schema／底表访问权。显式列出安全字段，不暴露 payload、签名、哈希、冲突原文或 worker 错误文本。
- 支付详情 deliveries 合并本支付 charge 投递和当前 batch_id 的 payout 投递；批次详情新增自己的 deliveries。每条统一为 `{id,eventId,eventType,outcome,receivedAt,status,result,subject}`，按 received_at、id 升序，subject 分别为 payment/batch。新 event_id 的相同事实投递 outcome 为 NEW，处理后 result 为 DUPLICATE_FACT；同 event_id 重投的 outcome 为 DUPLICATE。
- `GET /api/events/{eventId}` 返回 `{eventId,eventType,apiVersion,livemode,occurredAt,receivedAt,status,result,subject,subjectId,deliveries,asOf}`，不存在 404。deliveries 含该事件全部 NEW/DUPLICATE/CONFLICT 投递，按 received_at、id 升序；status/result 均为快照中的当前值。沿用 QuerySnapshot 的 readonly 连接、独立 REPEATABLE READ 事务和注入 Clock 的 asOf，不提供原文接口。

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

### P2 T08 实现备注（V7 银行流水）

- `io.imported_file.id` 用 `file_` 加 ULID；补 `status`（ACCEPTED/REJECTED）、`reject_reason`（FILE_INCOMPLETE/BALANCE_MISMATCH）、`raw bytea`、`recorded_at`。SHA-256 由 CHECK 校验原始字节；拒绝文件的日期、版本可空，接受文件必填。导入结果仅返回不可变的 `fileId/status/rejectReason`，重导不会返回变化后的匹配状态。
- `recon.bank_statement` 以 `file_id` 为主键和外键，保存 `account_ref/currency/opening_balance/closing_balance/period_start/period_end/version/recorded_at`。只允许关联 ACCEPTED 文件；T07 的单账户、CAD、单日日报约束落在数据库。文件版本不设唯一约束：同日报表不同字节仍各自存档，流水按身份合并。
- `recon.bank_line` 以 `(bank_txn_id, version)` 为主键；version 正整数，从 1 开始，仅 PENDING→BOOKED 时递增。`file_id` 引用 bank_statement；补 `recorded_at`。日期、金额、币种、方向、状态、reference、memo 均非空，return_of/reason_code 保留可空。wait_until 在插入时确定，之后不可改；MATCHED 必须带 matched_batch_id，其余状态不得带候选批次外键（候选放 issue.evidence_refs）。部分唯一索引保证同一批次最多一行 MATCHED。
- `bank_line.import_order bigint identity UNIQUE` 记录实际插入顺序，等待行按此升序处理；不以时钟相同的随机 ULID 或银行流水号排序。导入与资金写入在资金锁内串行化，序列空洞不影响顺序。只读视图也提供此列。
- migrator 为三表属主；app 仅 INSERT/SELECT，bank_line 额外只可 UPDATE(match_status, matched_batch_id)。触发器保护原文和原始流水字段，禁止删除、清空，包括属主误操作。readonly 仅 SELECT `recon.v_bank_statement`、`recon.v_bank_line`，无 io 或底表权限。
- 整文件解析、校验完成后在一个资金事务内归档并依文件顺序合并、匹配，先 lockFunds；格式失败只归档拒绝结果。同一 SHA 重导原样返回，无新行。POST `/api/bank-statements` 接受 text/csv 原始字节，可用 `X-Source-Name` 指定来源（默认 bank-statement.csv）；接受和拒绝均返回 200 及上述结果 JSON。

---

## 4. recon

### `recon.statement_line`、`recon.bank_line`

结算单行：`file_id`、`statement_line_id`、`line_type`、`processor_ref`、`batch_id`、`gross`、`fee`、`net`、`occurred_at`、`settlement_date`、`memo`（自由文本，P7 用于提示注入测试）。

### P2 T10 实现备注（V9：处理方快照）

- `recon.statement_line` 的主键为 `(file_id, line_seq)`，line_seq 为正整数；保留同一快照内重复的 txn 身份。补 `recorded_at`，金额为有界 BIGINT，净额等于毛额减手续费；PAYMENT/PAYOUT_FAILURE 为正毛额，REFUND 为负毛额。只追加，migrator 为属主，app 仅 SELECT/INSERT，readonly 仅读 `v_statement_line`；触发器限制只能引用已接受的处理方文件，并拒绝属主改删清空。
- `io.imported_file` 增加 `processor_fetch_ids` JSON 数组（默认空），关联 T04 原始分页存档；JSON 夹具不需要 fetch 编号。`processor_source` JSON 对象（默认空）保存传入 payout/lines JSON 的原始字符串，完整或拒绝的夹具都保留；HTTP 原始分页继续在 T04 存档。两列沿用文件的不可变性和最小授权，不向 readonly 暴露。处理方 raw 是规范化 JSON 内容（对象键和 payout/行排序，保留重复次数），sha256 仍由原有数据库 CHECK 校验；来源名、拉取时间和 fetch 编号不参与内容哈希。
- 文件 version 按业务日顺序递增；received_at 为全部拉取完成时刻。导入用独立的 PostgreSQL 事务 advisory lock 串行化 SHA 去重、版本比较与插入，不取得资金锁，不写账本。任一次读取不完整或响应契约非法，整份 REJECTED(FILE_INCOMPLETE)，零 statement_line；与银行文件一致，以文件拒绝原因呈现，不新增 issue 类别。行版本冲突仍开 INGESTION 差异。
- `v_input_evidence` 的 PROCESSOR 输入改为整个 PROCESSOR_STATEMENT 文件，不再把单个 RECON_SNAPSHOT fetch 当成完整快照；`v_processor_snapshot` 仅公开接受文件的 payout 元数据、编号与时点，含零行 payout，不暴露原始分页。两视图 app/readonly 仅读。相同行身份的新内容只追加并开 STATEMENT_VERSION_CONFLICT；对账按 fetched_at、recorded_at、file_id 确定最新快照中的该身份，重复次数只在这个快照内计数。

银行流水文件另记 `account_ref`、`currency`、`opening_balance`、`closing_balance`、`period_start`、`period_end`（INV-11）。

银行流水行：身份为 `(bank_txn_id, version)`（联合唯一，`bank_txn_id` 不再单独 UNIQUE）、`file_id`、`direction`（`CREDIT`/`DEBIT`）、`currency`、`status`（`BOOKED`/`PENDING`）、`amount`、`reference`（批次号或银行请求号）、`return_of` NULL（原流水或请求号）、`reason_code` NULL、`booking_date`、`value_date`、`memo`、`match_status`（`WAITING`/`MATCHED`/`UNAPPLIED`/`UNMATCHED_NO_POSTING`/`IGNORED_PENDING`）、`matched_batch_id` NULL、`wait_until` NULL。`app` 只可 UPDATE `match_status`、`matched_batch_id`。这里的 version 是同一流水的证据版本，当前有效版本为最高版本，不是按报表日期重置的文件版本。只有 PENDING→BOOKED 且其余内容（包括方向、金额、币种、reference、value_date）不变，才接受为状态前进；两份证据均保留，只有 BOOKED 参与匹配和记账。其他内容变化或 BOOKED→PENDING 均为 `STATEMENT_VERSION_CONFLICT`，旧行不动。

### `recon.run`

`id rr_…`、`kind`（`PROCESSOR`/`BANK`/`AGING`）、`input_file_ids`（含处理方快照编号）、`input_hashes`、`rule_version`、`cutoff_at`（业务截止，多伦多 00:00）、`evidence_as_of`（证据快照时点，contracts 1.2 第 3 条）、`started_at`、`finished_at`、`summary`（各类差异数）。CHECK `cutoff_at ≤ evidence_as_of`。

### P2 T09 实现备注（V8：运行与发现）

- `recon.run` 增加 `status`（RUNNING/FINISHED/FAILED）和 `recorded_at`；输入编号为 JSON 数组，哈希为按编号索引的 JSON 对象，summary 为 JSON 对象。`app` 可 INSERT/SELECT，仅可 UPDATE status、finished_at、summary；readonly 仅读 `v_run`。运行和全部发现、处置变化在一个 REPEATABLE READ 事务提交，异常全部回滚，不另开事务保存 FAILED；RUNNING 仅为事务内状态，FAILED 为预留值。
- finding 与 issue_transition 的 id 分别使用 `fnd_`、`itr_` 加 ULID。finding 的识别键在单次运行内唯一，expected/actual 允许 JSON null；evidence_refs 必须是数组，fingerprint 为 64 位小写十六进制。只有 TIMING_DIFFERENCE 必须有 due_at，其他类别必须为空。两表都由 migrator 拥有，app 仅 INSERT/SELECT，UPDATE/DELETE/TRUNCATE 另由 LL004 触发器保护属主误操作；readonly 仅读对应 `v_finding`、`v_issue_transition`。
- issue 新增可空 fingerprint、非负 reopen_count（默认 0），app 可 UPDATE 这两列；每次发现都把差异的当前内容（expected/actual/evidence_refs/fingerprint/last_run_id）刷新为本次发现，历史 finding 只追加不变，旧报告照样可重现。first_run_id、last_run_id 补 run 外键，v_issue 追加新列。
- T09b 的 V10 仅给 app 补 expected/actual/evidence_refs 三列的 UPDATE 授权，由对账运行按最新发现刷新；category、subject_type、subject_id 等身份字段仍不可改，INGESTION、其他运行类别和范围外差异不刷新。migrator 属主权限、readonly 仅读 v_issue 的授权不变；finding/issue_transition 的只追加保护不变。
- `recon.v_input_evidence` 仅投影 io 存档的编号、种类、哈希、接收/拉取时刻、recorded_at 和就绪标记，不暴露原文。银行文件编号沿用 file_…；T09 暂用 `fetch:<id>` 的处理方输入在 T10/V9 替换为完整快照的 file_…，原始 fetch 不再单独参与运行。app、readonly 仅 SELECT 此视图；UNION 视图写入在 PostgreSQL 重写阶段被拒（55000），测试另查 ACL 证明没有 INSERT/UPDATE/DELETE 授权。框架不直接查询 io。空 inputIds 表示在同一快照内选择该运行种类的全部就绪输入；显式编号要求种类匹配、就绪且两个存档时刻均早于 evidence_as_of。AGING 可使用两类输入。输入排序后固定到 run，GET 返回固定的输入和发现。

### `recon.finding`（P2，只追加）

`id`、`run_id` FK、`category`、`subject_type`、`subject_id`、`expected`、`actual`（jsonb）、`evidence_refs`、`fingerprint`、`due_at` NULL（只有 `TIMING_DIFFERENCE`）、`issue_id` FK、`recorded_at`。UNIQUE (`run_id`, `category`, `subject_type`, `subject_id`)。`app` 只 INSERT/SELECT，改删清空由触发器拒绝（LL004）。

### `recon.issue_transition`（P2，只追加）

`id`、`issue_id` FK、`from_status` NULL、`to_status`、`run_id` NULL、`reason`（`DETECTED`/`REOPENED`/`EXPLAINED`/`OPERATOR`）、`explanation`、`evidence_refs`、`recorded_at`。

### `recon.issue`

`id iss_…`、`category`、`subject_type`、`subject_id`（联合唯一键见状态机第 7 节）、`detected_by`（`RUN`/`INGESTION`/`AGING`；P1 的入站冲突用 `INGESTION`，不需要对账运行）、`first_run_id` NULL、`last_run_id` NULL、`status`、`expected`、`actual`（JSON，金额和引用）、`evidence_refs`、`explanation` NULL、`resolution_correction_id` NULL、`updated_at`。

`expected`、`actual`、`evidence_refs` 是当前发现内容，只能由对账运行按最新发现刷新；运营处置不改这三列。每次运行的原始内容保存在只追加的 `recon.finding` 中，旧报告不随 issue 刷新而改变。

差异类别（首版）：`MISSING_IN_LEDGER`、`MISSING_IN_STATEMENT`、`AMOUNT_MISMATCH`、`FEE_MISMATCH`、`DUPLICATE_STATEMENT_LINE`、`BATCH_TOTAL_MISMATCH`、`REFUND_NOT_IN_STATEMENT`、`DEPOSIT_MISSING`、`DEPOSIT_AMOUNT_MISMATCH`、`UNMATCHED_BANK_LINE`、`UNAPPLIED_CASH_OPEN`、`BANK_BALANCE_MISMATCH`、`BATCH_VERSION_CONFLICT`、`STATEMENT_VERSION_CONFLICT`、`TIMING_DIFFERENCE`、`PAYOUT_STATE_CONFLICT`、`BATCH_STATE_CONFLICT`、`REFUND_STATE_CONFLICT`、`UNSUPPORTED_PAYOUT`，入站类 `EVENT_CONTENT_CONFLICT`、`EVENT_BUSINESS_CONFLICT`、`UNSUPPORTED_EVENT`、`LIVEMODE_EVENT`，以及 contracts 1.3 节的老化类别。

### P1b-3 实现备注（V4 迁移）

- `id` 为 text 主键，`iss_` 加 26 位 ULID；`category`、`subject_type`、`subject_id`、`detected_by`、`status` 均为 text NOT NULL，两个 subject 字段不得为空白。类别 CHECK 包含上述 23 项及老化类别 `PAYMENT_NOT_SETTLED`、`DEPOSIT_NOT_RECEIVED`、`REFUND_AWAITING_FUNDS_AGED`、`REFUND_NOT_EXECUTED`、`PAYOUT_UNRESOLVED`、`EVENT_PARKED_AGED`。
- `first_run_id`、`last_run_id` 为可空 text；`RUN` 必须有 `first_run_id`，`INGESTION` 的两个运行号必须都为空，`AGING` 不额外限制运行号。到 `recon.run` 的外键在 P2 建表时补上。
- `status` 限于 `OPEN`、`EXPLAINED`、`RESOLVED`、`ACCEPTED`。`EXPLAINED`、`ACCEPTED` 要求 `explanation IS NOT NULL`；`RESOLVED` 要求 `explanation` 或 `resolution_correction_id` 至少一个非 NULL；`OPEN` 不限制两者。两列为可空 text，纠错外键在 P4 建 `ledger.correction` 时补上。
- `expected`、`actual` 为可空 jsonb；`evidence_refs` 为 jsonb NOT NULL，默认 `[]`，CHECK 为 JSON 数组。`recorded_at`、`updated_at` 为 timestamptz NOT NULL。
- 显式命名所有 CHECK 和 UNIQUE；识别键为 UNIQUE (`category`, `subject_type`, `subject_id`)。关闭后再次出现的处理见 state-machines 7.1 节（P2 新增 `recon.finding`、`recon.issue_transition`，`recon.issue` 加 `fingerprint`、`reopen_count` 两列）。
- `app` 有 SELECT、INSERT，仅可 UPDATE (`status`, `last_run_id`, `explanation`, `resolution_correction_id`, `updated_at`)，无 DELETE/TRUNCATE；`migrator` 为属主；`readonly` 仅可 SELECT 显式列出全部字段的 `recon.v_issue`。
- V4 将 V3 的 `inbox_event_check1`、`inbox_event_check2` 分别重命名为 `inbox_event_status_result`、`inbox_event_status_parked_on`，约束逻辑不变。

---

## 5. 模拟器（独立 schema，主应用不可见）

- `sim_processor`（fake Stripe）：charge、refund、balance transaction、payout 的“真实”状态，字段命名跟 Stripe 一致；webhook 投递日志（每次尝试、是否故意重复/乱序/丢失/篡改）；结算单生成记录。
- `sim_bank`：账户余额、收到的付款请求（按 `bank_request_id` 去重）、流水行、退回计划、API 故障计划（超时、丢回执）。
- `sim_chaos`：chaos relay 每次转发、复制、延迟、乱序、丢弃、丢回执的日志（两种模式都用）。
- `sim_truth`：场景编号、种子、注入的故障清单和预期差异（ground truth），对账测试和 Agent 评测共用。

处理方和银行的状态分开存、分开推进，避免两边共享同一个错误（I6）。

### P2 T12 实现备注（场景证据导出，无新迁移）

- 场景正本、动作与人工预期发现存于 `simulator/src/main/resources/scenarios/*.json`；导出格式和命令见 [场景夹具](../testing/scenario-fixtures.md)。`truth.json` 仅替换外部别名，不从主应用数据推导。
- 场景运行复用现有 `sim_processor`/`sim_bank` 表及服务，在空模拟器库上执行后回滚，导出事件原文、按请求的处理方 REST 响应及银行实际执行流水。运行接口返回证据包，不创建新的持久化会话或表；已有人工作业数据时返回 409。
- `sim_processor.fault_plan.request` 增加 `omit_transaction` 与 `amount` 故障字段：前者在明细读取时隐藏指定已存在的 txn，后者改变后续 payout 对象的金额。原已交付事件及银行已接收转账仍保留原数值。charge 请求可用 `available_on` 指定整数 epoch 秒，写入既有 balance_transaction.available_on，控制后续批次分配。
- 主应用回放仅消费已提交的资源文件；比较支付 subject 时按处理方编号定位本地编号，不读取模拟器数据库。金额和预期发现仍来自 JSON 场景。数据库角色权限不变。

### P2 T07 实现备注（模拟器 V4）

- 全部新表及 identity 序列由 `simulator` 迁移、拥有；显式撤销 PUBLIC、migrator、app、readonly 权限；每表有 `recorded_at timestamptz`，取注入的模拟 Clock。银行无处理方表外键，也不读取处理方 schema。
- `sim_bank.account`：`account_ref text PK`、`currency text`（CAD）、`balance bigint`（非负且小于 2^53）。单账户 `SIM-CAD-001` 由银行首次操作创建，初始零余额；账户行锁串行化银行推进、收通知、计划替换和文件版本分配。
- `sim_bank.fault_plan`：`target_ref text PK`（`*` 是默认规则，payout 号规则优先）、`scenario text`、`fault text`、`parameter bigint`、`memo text`。单笔转账选择一种故障，NONE 清除效果；计划仅影响后来收到的转账。
- `sim_bank.incoming_transfer`：`payout_ref text PK`、`account_ref FK`、`claimed_amount bigint`（正且小于 2^53）、`plan jsonb`（对象，接收时冻结）、`due_date date`、`executed boolean`。重复通知同金额幂等，不同金额拒绝；仅标准自动 CAD 正 payout 通知银行。超出单行上限的总额按 99999999 分分行，不能生成越界银行行。
- `sim_bank.txn`：`bank_txn_id text PK`（外部身份由 payout 号及分行序号确定）、`account_ref FK`、`payout_ref FK incoming_transfer`、`direction text`、`amount bigint`（1～99999999）、`currency text`（CAD/USD）、`status text`（BOOKED/PENDING）、`reference text`、`booking_date/value_date date`、`visible_date date`、`memo text`。visible_date 是首次出现在文件的日期；PENDING_FIRST 的 booking_date/value_date 从开始就定为次日，次日只前进 status，余额只增加一次。历史文件按这两个日期重建当日状态。
- `sim_bank.statement`：`(account_ref FK, statement_date, version)` 主键，version 正整数；`content text` 保存完整未损坏 CSV，`body text` 保存不含版本号的规范内容，用于同日报表比较；所有版本保留，corrupt 仅改响应。
- `sim_truth.injected_fault`：`id bigint identity PK`、`scenario text`、`object_ref text`、`fault text`、`expected_category text`。每次实际应用故障（非设置计划）追加一行，与转账接收或坏文件导出同事务。SHORT/SPLIT → DEPOSIT_AMOUNT_MISMATCH，DUPLICATE/FOREIGN_CURRENCY/UNKNOWN_CREDIT → UNMATCHED_BANK_LINE，DELAY_DAYS/PENDING_FIRST → TIMING_DIFFERENCE（延迟超期后可升级 DEPOSIT_NOT_RECEIVED）；trailer_count/trailer_sum → FILE_INCOMPLETE，balance → BANK_BALANCE_MISMATCH（导入阶段文件拒绝）。

### P2 T03 实现备注（模拟器 V3）

- 新表仍由 `simulator` 迁移并拥有，PUBLIC、migrator、app、readonly 均无访问权。`payout`、`balance_transaction`、`fault_plan` 均有非空 `recorded_at timestamptz`，取注入的现实 UTC Clock；业务时间来自 SimulationClock。新表业务时刻存 `timestamptz`，REST 转成 Stripe Unix 秒，V1 的 bigint 时间列不变。
- `payout`：`id text PK`、`amount bigint`（允许故障注入负值）、`currency text`、`automatic boolean`、`method text`（standard/instant）、`status text`（pending/in_transit/paid/failed/canceled）、`reconciliation_status text`（in_progress/completed/not_applicable）、`arrival_date/created timestamptz`、`failure_code text NULL`；另有 `duplicate_transaction text NULL`、`fail_page integer NOT NULL DEFAULT 0`（0 关闭，正数为失败页）。
- `balance_transaction`：`id text PK`、`type text`（charge/refund/payout/payout_failure/adjustment）、`source text`、`amount/fee/net bigint`、`currency text`、`created/available_on timestamptz`、`payout text NULL FK payout`、`reporting_category text`。CHECK `net = amount - fee`，金额各列和 payout 金额严格位于 ±2^53 以内。source 不设 charge 外键，以支持未知支付故障及 payout/refund 来源。
- `fault_plan`：`id text PK`、`target_id text`、`request text`（原始控制 JSON）、`recorded_at timestamptz`。charge 的 fee 覆盖、payout 参数覆盖和每次故障计划修改均追加记录。列表复制行仅在响应中生成；extra_line 是带全新 txn 编号的持久行，直接属于指定 payout，不改变 payout 金额；fail_page 按当前游标偏移/limit 推导，重复请求同一页稳定失败。
- `event.charge_id` 在 V3 改成可空，原外键保留；payout 事件使用新增 `payout_id text NULL FK payout`，旧 charge 事件不变。两种事件均保存完整对象快照，redeliver 原样重签，reissue 保留类型及对象快照。
- 资金写入事务首先以 SHARE ROW EXCLUSIVE 锁定 balance_transaction 表，防止多个请求或进程重复分配。默认 payout 归集已可用、未分配的 CAD 行（不含 payout 自身），负/零净额不创建；失败不解绑定旧行，只新增一条 payout_failure 返还行。amount 覆盖是故意制造不一致的故障：仍归集原集合，payout 借记采用覆盖金额，后续故障场景需独立重置数据，不承诺自动修复不一致。

### P1b-8 chaos relay（模拟器 V2）

`sim_chaos.relay_log` 由 simulator 迁移、拥有；PUBLIC、migrator、app、readonly 无表和序列访问权。
字段：`id bigint identity PK`、`receipt_id uuid NOT NULL`（每次接收独立编号）、`event_id text NULL`（无法解析时为空）、`action text NOT NULL`（PASS / DUPLICATE / DELAY / HOLD_AND_REORDER / DROP / HOLD_TIMEOUT_RELEASE）、`attempt_no integer NOT NULL`、`http_status integer NULL`、`error text NULL`、`recorded_at timestamptz NOT NULL`、`payload_sha256 text NOT NULL`（64 位小写十六进制）。
同一 receipt 的 attempt_no 唯一；0 是接收日志（状态码和错误均空），1 起是转发结果（状态码 100～599 或错误恰好一个非空）。DROP 只有接收记录；HOLD_TIMEOUT_RELEASE 只用于转发。recorded_at 使用可控模拟时钟，便于复现场景。日志不保存签名或请求体。

### P1b-7 fake Stripe 最小版（模拟器 V1）

独立 Maven 项目 `simulator/`，迁移和运行都用 `simulator`，历史表在已有的 `sim_processor`，禁止 Flyway 建 schema。三张表由 simulator 拥有；PUBLIC、migrator、app、readonly 均不授访问权。

- `charge`：`id text PK`（ch_）、`payment_intent text`（pi_）、`amount bigint`、`amount_captured bigint`、`currency text`、`captured boolean`、`status text`、`livemode boolean`、`created bigint`（Stripe Unix 秒）、`recorded_at timestamptz`，全部非空。准入失败场景允许覆盖金额、币种、请款状态和 livemode，不在模拟器重复主应用的准入限制。
- `event`：`id text PK`（evt_）、`charge_id text FK charge`、`type text`、`api_version text`、`created bigint`（Unix 秒）、`payload text`（原文 JSON，按 UTF-8 投递）、`recorded_at timestamptz`，全部非空。
- `webhook_delivery`：`id bigint identity PK`、`event_id text FK event`、`target_url text`、`signed_at bigint`（Unix 秒）、`http_status integer NULL`、`error text NULL`、`recorded_at timestamptz`；状态码与错误恰好一个非空。其余字段非空。

创建 charge 和事件在一个事务提交后投递；失败不自动重试。redeliver 使用存档原文；reissue 复制原事件的 Charge 快照，仅换事件编号及事件创建时刻。模拟时钟影响对象/事件 created，签名和 recorded_at 使用独立的现实 UTC 时钟；同一进程的同事件连续投递至少递增一秒（参考已存日志），确保重投签名时间更新。
