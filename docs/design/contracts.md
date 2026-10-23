# 时间契约与稳定编号

P0 设计稿 v0.2，2026-10-21（同日开工前审查修订）。对应复核 I5（业务编号永久唯一、三种时间分开）。

---

## 1. 时间契约

### 1.1 每条记录最多有这几种时间

| 字段 | 含义 | 谁给的 | 用途 |
|---|---|---|---|
| `occurred_at` | 业务事实在外部世界发生的时刻（处理方请款时刻、退款执行时刻、银行记账时刻）。每类事实取哪个外部字段见 [stripe-integration.md](stripe-integration.md) 第 4 节；退款执行时刻**不是** Refund 对象的创建时刻 | 外部证据 | 对账截止、结算单归日、老化计算的起点 |
| `received_at` | 我们收到这条证据的时刻（webhook 落进 inbox、文件导入） | 本系统时钟 | 乱序和延迟分析、处理顺序 |
| `recorded_at` | 本系统提交状态变更或分录的时刻 | 本系统时钟（事务内取一次） | 审计、“当时系统知道什么” |
| `signed_at` | webhook 签名头里的时间戳 | 处理方 | **只用于**防重放窗口（±5 分钟），绝不当业务时间 |
| `value_date` / `booking_date` | 银行流水的起息日、记账日（`date`，不带时区） | 银行流水 | 银行侧对账 |
| `settlement_date` | 处理方批次所属结算日（`date`） | 处理方 | 处理方侧对账 |

### 1.2 规则

1. 时刻一律存 `timestamptz`，统一按 UTC 解释；日期（`date`）只用于外部明确给出的“哪一天”。
2. **业务日按 `America/Toronto` 划分**：某个时刻属于哪一天，由 `occurred_at` 换算到多伦多本地时间决定。夏令时切换日照样按本地日历日算（这一天有 23 或 25 小时）。
3. 对账运行有**两个时点**，分开记录（2026-10-23 定，P2 开工前置 ①，开工前审查 A-I3、B-I5）：
   - **业务截止 `cutoff_at`**：决定**哪些义务要被检查**（范围）。只检查 `occurred_at < cutoff_at` 的支付、批次和退款；只带日期的银行流水行按 `booking_date < cutoff_at 换算成的多伦多日期` 判断。`cutoff_at` 必须是多伦多本地 00:00，即一个业务日的开头。
   - **证据快照时点 `evidence_as_of`**：决定**可以用哪些证据来匹配和解释**。本地记录只用 `recorded_at < evidence_as_of` 的；导入的文件和处理方快照只用 `received_at`／`fetched_at < evidence_as_of` 的。证据的业务日期可以晚于 `cutoff_at`：例如 11-10 确认的批次被 11-12 记账的银行入账解释，这不是差异。要求 `cutoff_at ≤ evidence_as_of ≤ 运行开始时刻 − 证据宽限`（`ledgerlab.recon.evidence-grace`，默认 60 秒，大于任何资金事务的时长），保证运行开始后才提交、却带着更早 `recorded_at` 的事务不会让重跑结果变化。
   - 范围内的义务在证据里找不到对应项时：还没到期 → `TIMING_DIFFERENCE`（带 `due_at`）；已到期 → 对应的缺失类或老化类差异（第 8 条、1.3 节）。
   - 运行记录固定 `cutoff_at`、`evidence_as_of`、规则版本和全部输入文件／快照的编号与哈希；同样这几项重跑，结果（发现集合）必须一样（AC-5.9）。不用账本 `seq` 当版本：`seq` 是分配顺序，不是提交顺序。
   - 处理方快照“拉取的是哪一段”与上面两个时点无关：快照按 `created` 区间拉（通常是前一个业务日），凌晨拉的前一日快照因为 `fetched_at < evidence_as_of` 照常可用。
4. 系统里所有“现在”都来自注入的 `Clock`；测试和模拟器用可控时钟。业务代码不直接调用系统时间。
5. 模拟器有自己的模拟时钟，可以快进；它产生的证据里的 `occurred_at` 来自模拟时钟，我们这边的 `received_at` 来自我们的时钟，两者天然可能不一致，这正是要测的。
6. 迟到证据（`received_at` 远晚于 `occurred_at`）照常处理，按 `occurred_at` 归日；已经出过的对账报告不改，下一次运行把相关差异转为 `EXPLAINED`。
7. 老化阈值和结算日按**工作日**计：周末不算，假日来自仓库里的一份静态日历文件 `src/main/resources/calendar/business-holidays.csv`（模拟合同约定：采用 Payments Canada 的非处理日，按年维护，2026 和 2027 两年；**11 月 11 日是非工作日**）。“距起点 n 个工作日”= 从起点所在业务日之后的第一个工作日起数 n 个工作日，到期时刻是第 n 个工作日结束（次日多伦多 00:00）。测试要覆盖跨周末、假日和夏令时切换日。`America/Toronto` 是我们模拟合同里的约定，不是行业默认。
8. 跨日的行（例如 23:50 请款、次日才出现在结算单）先记为未完成的时间差 `TIMING_DIFFERENCE`，带到期时间；在到期前被后续证据解释就转 `EXPLAINED`，到期仍未出现就升级成对应的缺失类差异。时间差不永久等同“正常”。
   到期时间统一取该义务在 1.3 节的老化阈值（例如支付是请款业务日起 2 个工作日）：**未到期 = `TIMING_DIFFERENCE`，到期 = 老化类差异**，两者是同一个义务在不同时间的两种状态，同一次运行里不会同时出现。升级时旧的 `TIMING_DIFFERENCE` 转 `EXPLAINED`（说明“已升级为 …”），新开老化差异。

### 1.3 老化阈值初值（可配置，ADR-0010）

| 义务 | 起点 | 阈值 | 差异类别 |
|---|---|---|---|
| 支付请款后还没进入已确认批次 | 支付 `occurred_at` | 2 个工作日 | `PAYMENT_NOT_SETTLED` |
| 批次已确认、银行还没入账 | 批次 `occurred_at` | 2 个工作日 | `DEPOSIT_NOT_RECEIVED` |
| 退款等待资金 | 受理时刻 | 5 个工作日 | `REFUND_AWAITING_FUNDS_AGED` |
| 退款已提交、还没执行 | 提交时刻 | 3 个工作日 | `REFUND_NOT_EXECUTED` |
| 提现结果未知或已受理还没出账 | 提交时刻 | 1 个工作日 | `PAYOUT_UNRESOLVED` |
| 入站事件停在 `PARKED` | `received_at` | 1 小时 | `EVENT_PARKED_AGED` |

老化逐笔检查每一笔未了结的义务，而不是看账户总额（修订 R1）。

### P2 T01 实现备注（手续费与工作日日历）

- 共享纯函数 `dev.ledgerlab.shared.Fees.contractFee(long)` 实现第 3 节公式，乘法、舍入加法和固定费用加法均用 `Math.multiplyExact` / `Math.addExact`，中间值溢出抛 `ArithmeticException`；支付金额准入仍由调用方负责。`PaymentCapture` 改用此函数，合法支付的结果不变。
- `dev.ledgerlab.shared.BusinessCalendar` 是不可变 Spring bean，启动时以 UTF-8 输入流读取 classpath 的 `calendar/business-holidays.csv`（打包进 JAR 后同样可读），不读取系统当前时间。`businessDateOf` 返回多伦多本地日历日，周末、假日也有归属日期；`isBusinessDay` 才判断工作日。
- 日历维护范围明确为 2026–2027，不由 CSV 首末条假日推断。2026 日期核对 [Payments Canada 非处理日表](https://www.payments.ca/system-closure-schedule)；2027 按同一联邦假日集合和模拟合同的周末顺延规则列出。2026 Boxing Day 补休为 12-28；2027 Christmas Day、Boxing Day 分别补休为 12-27、12-28，不占用同一天。这是模拟合同约定，不是行业默认。
- `addBusinessDays(date, n)` 不计起始日，`n = 0` 原样返回起始日期（包括非工作日），负数拒绝；`dueAt(start, n)` 要求 `n > 0`，返回第 n 个工作日的次日多伦多 00:00。`startOfDay` 使用当地日历边界，不能用固定 24 小时替代。
- 所有日期方法按多伦多日期检查覆盖范围，起点、遍历日期或结果超出 2026–2027 均抛 `IllegalArgumentException`；包括到期结果落在 2028-01-01 的情形。需扩年时必须同时维护 CSV 和代码中的覆盖年份，不能默认为工作日。
- T01 单测覆盖 [验收条件](../acceptance-criteria.md) 第 5.3 节标准样例的手续费与 P4/P5 到期计算（支持 AC-5.7）、工作日老化边界（支持 AC-5.11），不代表完整对账与老化流程已实现。

---

## 2. 稳定编号

### 2.1 本系统生成的编号

格式：`前缀_` + ULID（26 位，按时间可排序）。前缀让人一眼看出对象类型，也让“把退款号填进提现号字段”这类错误在校验时就被拒绝。

编号由注入的 `IdGenerator` 生成，和 `Clock` 一样业务代码不直接调随机数。测试和空库重建时用带种子的生成器（时间取自注入的时钟），同样的输入序列得到同样的编号（AC-4.13）。分录的 `source_key` 能用外部编号的就用外部编号（例如 `payment:{processor_payment_ref}:captured`），不依赖本地编号。

| 对象 | 前缀 | 例子 | 唯一范围 |
|---|---|---|---|
| 支付（本地） | `pay_` | `pay_01JB…` | 永久 |
| 退款 | `rfd_` | `rfd_01JB…` | 永久 |
| 商户提现 | `mpo_` | `mpo_01JB…` | 永久（不用 `po_`，那是 Stripe payout 的前缀） |
| 分录 | `je_` | `je_01JB…` | 永久 |
| posting | `pst_` | | 永久 |
| 出站命令 | `cmd_` | | 永久 |
| 对账运行 | `rr_` | | 永久 |
| 对账差异 | `iss_` | | 永久 |
| 纠错 | `cor_` | | 永久 |

### 2.2 外部编号（原样保存，单独建唯一约束）

| 编号 | 来源 | 唯一范围 | 用途 |
|---|---|---|---|
| `event_id` | 处理方 webhook | 永久（inbox 不清理） | 入站去重 |
| `processor_payment_ref` | 处理方 | 永久 | 支付匹配 |
| `processor_refund_ref` | 处理方 | 永久 | 退款匹配；同一支付的多次部分退款各有各的编号，不是重复 |
| `batch_id` | 处理方 | 永久 | 批次匹配、银行入账匹配 |
| `statement_line_id` | 处理方结算单 | 永久 | 结算单行身份。同一文件换行、排序或格式变化导致内容哈希变了，行身份仍然按它去重；同一行编号不同内容 → 版本冲突 |
| `bank_txn_id` | 银行流水 | 永久 | 流水去重；同一批次的两笔真实入账有两个不同编号，不是重复通知 |
| `bank_request_id` | **我们**生成，发给银行 | 永久 | 提现请求的幂等键，等于 `mpo_` 编号，所有重试都用它 |

### 2.3 三层业务去重，各管各的（ADR-0009）

另有入站 webhook 按 `event_id` 去重（ADR-0007），AC-2 把它和这三层合称“四层”。

| 层 | 键 | 寿命 | 防的是什么 | 重复时的行为 |
|---|---|---|---|---|
| API 幂等记录 | `(client_id, Idempotency-Key)` | 24 小时后可以清理 | 客户端网络重试 | 同键同内容：返回第一次的响应；同键不同内容：`422`；同键并发首次：`409` |
| 业务操作编号 | 退款：`(payment_id, merchant_refund_ref)`；提现：`merchant_payout_ref` | **永久** | 幂等记录过期以后的重放、跨渠道重复提交 | 返回原操作的结果（不是对象当前状态）；原结果永久存在业务对象的 `initial_result` 上，不依赖会被清理的幂等记录 |
| 分录 `source_key` | 见 money-model 第 3 节 | 永久 | 同一业务事实被不同证据各记一次（例如 webhook 和批次行都说退款已执行） | 不再记账，事务里其余部分照常 |

“返回原操作的结果”指：第一次受理退款时返回的是 `AWAITING_FUNDS`，三天后同一个 `merchant_refund_ref` 再来，返回的仍是那一次受理的结果和退款号，客户端再用退款号查当前状态。

---

## 3. 金额

- 一律 `BIGINT` 整数分，Java 里用 `long` 包在值类型里，不用 `double`/`float`，JSON 里传整数。
- 手续费：`30 + (gross × 29 + 500) div 1000`，即 2.9% 四舍五入到分再加 30 分。例：10000 → 320；1234 → 36 + 30 = 66；50 → 1 + 30 = 31。
- 各类金额的合法范围：

| 金额 | 范围 | 越界时 |
|---|---|---|
| 支付（请款事件） | 50 ～ 99999999 分（Stripe CAD 下限 50 分；Stripe 金额最多 8 位） | 事件 `REJECTED(INVALID_AMOUNT)` 并告警 |
| 退款（API） | 1 分 ～ 该支付剩余可退额 | 400 `INVALID_AMOUNT` 或 409 `REFUND_EXCEEDS_REMAINING` |
| 提现（API） | 1 ～ 99999999 分，并受两个门槛限制 | 400 `INVALID_AMOUNT` 或 409 `INSUFFICIENT_FUNDS` |
| 银行流水行、批次行 | 1 ～ 99999999 分 | 整个文件或批次拒绝 |
| 账户余额、批次合计、队列合计等汇总值 | 绝对值 < 2^53（9007199254740991） | 数据库 CHECK 拒绝；Java 用 `Math.addExact` 等精确运算，溢出即回滚 |

- 汇总值限制在 2^53 以内，是为了 JSON 传到前端（JavaScript `Number`）时不丢精度。PostgreSQL 的 `SUM(bigint)` 返回 `numeric`，转回 `long` 前要做范围检查。
- 合同手续费在 50 分起的支付上恒小于毛额；处理方报来的**实际**手续费如果大于该行毛额，批次 `HELD(FEE_EXCEEDS_GROSS)`（money-model 第 3 节）。
- 对账金额首版**零容差**（修订 R4）。

## 4. 外部数据契约（处理方结算数据与银行流水）

- **处理方结算数据**是一次 API 拉取快照：拉 `created` 落在指定区间（通常是一个多伦多业务日）的全部 payout，再对每个 payout 拉 `balance_transactions?payout=` 的全部分页（直到 `has_more=false`）；原始响应整体存档并算哈希，作为一个“结算单版本”，`fetched_at` 记拉取时刻（参与 1.2 第 3 条的证据时点判断）。分页没拉完的快照不能用于对账（`FILE_INCOMPLETE`）。模拟器模式下从 fake Stripe 拉，形式相同。
- **银行流水**是文件：每个文件有文件头（文件类型、账户、日期范围、版本号）和文件尾（行数、金额合计；银行流水另有期初、期末余额）。行数或合计对不上 → 整个文件拒绝导入（`FILE_INCOMPLETE`），保留原始文件。
- 同一文件内容（SHA-256）重复导入：直接返回原导入结果。
- 同一日期、同一账户的新版本文件：按行身份合并。银行流水的证据身份为 `(bank_txn_id, version)`，当前有效版本为该流水的最高版本；这里的行版本独立于文件版本。同一 `bank_txn_id` 只有 PENDING→BOOKED 且其余内容（包括方向、金额、币种、reference、value_date）不变，才接受为状态前进，两份证据均保留，只有 BOOKED 参与匹配和记账。其他内容变化或 BOOKED→PENDING → `STATEMENT_VERSION_CONFLICT`，旧行不动；完全相同的行不重复记账。
- 银行流水首版是自定义 CSV，字段参照 camt.053（Mambu 说明）选取，不解析完整 XML。

### 4.1 银行 CSV 文件格式（P2 T07，T08 解析契约）

`GET /sim/bank/statements/{date}` 中 date 为 `America/Toronto` 日期 `YYYY-MM-DD`；只可导出模拟当天或过去日期，日历覆盖 2026–2027。响应为 `text/csv;charset=UTF-8`，UTF-8 无 BOM，记录分隔符 CRLF，末记录也有 CRLF。无列名行，无空白记录；字段含逗号、双引号、CR 或 LF 时加双引号，内部双引号写成两个双引号（包括 memo；解析不能直接按换行或逗号 split）。

```csv
H,BANK_STATEMENT,SIM-CAD-001,2026-11-09,2026-11-09,1,CAD
D,bt_po_example_1,CREDIT,9680,CAD,BOOKED,po_example,2026-11-09,2026-11-09,Simulated bank transfer
T,1,9680,0,0,9680
```

- 头恰好 7 个字段：`H,BANK_STATEMENT,<account>,<period_start>,<period_end>,<version>,CAD`。首版单账户 `SIM-CAD-001`、单日日报，两端日期相等且均包含在期间内；版本按 `(account, 报表日期)` 从 1 开始。
- 明细恰好 10 个字段：`D,<bank_txn_id>,<CREDIT|DEBIT>,<amount>,<currency>,<BOOKED|PENDING>,<reference>,<booking_date>,<value_date>,<memo>`。日期是 ISO 本地日期；金额是无小数点的正整数分（1～99999999），方向由 CREDIT/DEBIT 表示。正常币种 CAD，FOREIGN_CURRENCY 故障故意写 USD；金额数值保持原样，模拟器不做外汇换算。
- 尾恰好 6 个字段：`T,<line_count>,<sum_credit>,<sum_debit>,<opening_balance>,<closing_balance>`。line_count 是 D **记录**数（包括 PENDING，memo 内换行不增加记录数）；credit/debit 仅合计 BOOKED 记录，`opening + credit − debit = closing`，整数分精确运算。期初为本日前全部实际 BOOKED 净额；PENDING 不改变余额；空文件也有 H/T，计数与收支合计为 0、期初等于期末。币种故障只改变行币种标签，尾合计仍按该 BOOKED 行数值计算。
- 正常流水在银行实际 booking_date 的文件里出现；PENDING_FIRST 首次在原定下一个工作日出现，booking_date/value_date 从开始就固定为其**下一日历日**（可以是周末）；次日同一 bank_txn_id 只把 status 变成 BOOKED。第一日 PENDING、第二日 BOOKED 的两份文件各从 v1 开始；不要求两个日期的文件版本递增。重导历史日期仍恢复当日状态，不把旧 PENDING 文件改成 BOOKED。
- 同一日重导规范内容不变时返回相同版本和字节；内容变化才版本 +1，保留已导出的全部干净版本。明细排序为 booking_date、bank_txn_id；文件版本持久化，进程重启后继续计数。
- 可选 `corrupt=trailer_count|trailer_sum|balance` 分别把尾部行数、入账合计、期末余额加 1，其他内容不动；只损坏响应，不修改银行流水、余额、已存干净文件或版本。未知参数值返回 400。每次坏文件注入记入 sim_truth；T08 按原始文件保留和整份拒绝规则处理。
