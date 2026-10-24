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
| 批次 `HELD(UNKNOWN_PAYMENT)` | 批次 `occurred_at`（payout created） | 2 个工作日，`ledgerlab.recon.aging.held-batch-business-days` | `MISSING_IN_LEDGER`，subject 为本地缺失的支付号 |
| 退款已执行、尚未进入处理方快照 | 成功历史的执行 `occurred_at` | 2 个工作日，`ledgerlab.recon.aging.refund-statement-business-days` | `REFUND_NOT_IN_STATEMENT`（到期前为 `TIMING_DIFFERENCE`） |
| 退款等待资金 | 受理时刻 | 5 个工作日 | `REFUND_AWAITING_FUNDS_AGED` |
| 退款已提交、还没执行 | 提交时刻 | 3 个工作日 | `REFUND_NOT_EXECUTED` |
| 提现结果未知或已受理还没出账 | 提交时刻 | 1 个工作日 | `PAYOUT_UNRESOLVED` |
| 入站事件停在 `PARKED` | `received_at` | 1 小时，`ledgerlab.recon.aging.parked-event` | `EVENT_PARKED_AGED` |

老化逐笔检查每一笔未了结的义务，而不是看账户总额（修订 R1）。

### P3 T11 实现备注：退款对账与老化

- PROCESSOR 升级为 `processor-v2`；BANK／AGING 沿用共用版本配置，升级为 `p3-v2`（BANK 算法不变）。ALL 顺序执行三种规则，自动包含退款检查。读取既有运行无需重新计算；重跑须保持版本、阈值配置、截止及证据时点一致。
- 已执行退款按 `core.refund_transition` 在 `recorded_at < evidence_as_of` 的最后一条历史恢复状态，同刻按 id 降序。SUCCEEDED 的执行 `occurred_at < cutoff_at` 才产生缺行义务；缺行的到期判定用 cutoff，和支付／批次的外部证据缺失一致。退款按 `re_` 逐笔匹配，金额比较负 gross 与本金相反数；部分退款不按支付号合并。混合批次总额包含退款的负 net。
- AWAITING_FUNDS 的起点为不可变 accepted_at，阈值 `ledgerlab.recon.aging.refund-awaiting-funds-business-days` 默认 5；SUBMITTED／UNKNOWN 及已发送的 READY 的阈值 `ledgerlab.recon.aging.refund-execution-business-days` 默认 3。三个退款工作日阈值必须为正。
- 退款等待和未执行是本地未完成操作，和 PARKED 一样用 evidence_as_of 判断当时状态与是否到期，不借用支付／批次的业务 cutoff；未到期不产生老化发现。expected 保存 start_at、threshold_business_days、due_at、status 和已结束工作日数。到期只报告差异，不改变退款状态、额度、命令或账本。
- 提交起点优先使用命令不可变 first_sent_at（命令 recorded_at 和 first_sent_at 都严格早于 evidence_as_of）；无发送记录时使用该时点前首次 SUBMITTED 历史的 occurred_at，重建 T07 外部证据回退起点。不会读取当前可变 submitted_at／resolved_at 来判断旧运行。READY 没有当时可见的 first_sent_at 不报未执行。
- 处理方退款号绑定后沿用 T07 的不覆盖语义；匹配时还要求当时已有 SUBMITTED／SUCCEEDED／FAILED 处理方结果历史，避免把后来才关联的 re_ 借给旧时点仍 READY／UNKNOWN、尚无处理方结果的退款。命令和历史证据引用同样限制在 evidence_as_of 之前。
- 已放行、完成的退款仍纳入对应老化检查范围，后续运行能解释旧 OPEN 差异；已执行缺行到期时，PROCESSOR 同次运行将旧时间差解释为“已升级为 REFUND_NOT_IN_STATEMENT”，新缺行差异为 OPEN。后续快照出现时解释缺行，旧 finding 保留。

### P3 T01 实现备注：PARKED 老化

- 从 `io.inbox_status_history` 取 `recorded_at < evidence_as_of` 的最后一行（同刻按 id 排序），当时为 PARKED 且 `received_at + parked-event ≤ evidence_as_of` 时产生 `EVENT_PARKED_AGED`。这是接收时间的小时阈值，不按业务 cutoff 或工作日计算；起点不会因重复投递、重试、唤醒而重置。配置为正 Duration，默认 `PT1H`。
- subject 为 `(event, evt_…)`；expected 保存 `start_at`、ISO-8601 Duration `threshold`、`due_at` 和当时 `parked_on`；证据引用事件和选中的历史行。未到期不产生时间差。已恢复事件仍在检查范围，沿用已有 OPEN → EXPLAINED／人工处置语义；旧证据时点重跑保留相同发现。
- 延续 BANK／AGING 共用规则版本配置的实现，两者版本升级为 `p3-v1`（BANK 算法未改），PROCESSOR 仍为 `processor-v1`；旧运行报告仍可直接读取。重跑仍须使用相同版本及阈值配置。limitations 删除 P2 未检查 PARKED 的说明，改为回填时刻之前无法还原 inbox 状态。

### P2 T11 实现备注：银行、老化与全套运行

- BANK、AGING 使用 `p2-v1`，PROCESSOR 保持 `processor-v1`。支付／批次入账／HELD 的阈值分别为 `ledgerlab.recon.aging.payment-business-days`、`deposit-business-days`、`held-batch-business-days`，默认均 2，必须为正数；PROCESSOR 的支付时间差使用同一支付阈值。重放须保持规则版本及阈值配置不变。老化 expected 保存 `start_at`、`threshold_business_days`、`due_at`、截至 cutoff 已结束的工作日数及说明。
- BANK 对截止前已确认、证据时点尚未失败的批次找银行入账；银行证据可晚于 cutoff，但导入时间和记账时间均须早于 evidence_as_of。未到期报批次时间差，到期交给 AGING 的 `DEPOSIT_NOT_RECEIVED`；P2 不产生保留类别 `DEPOSIT_MISSING`，**缺失按老化归类**。
- 批次、支付状态取 evidence_as_of 前最后一条只追加历史。银行匹配结果从该时点前的分录还原：`BANK_DEPOSIT_RECEIVED` 为已应用，2105 的逐笔 posting 净额为未清款；BOOKED/CAD/CREDIT、有不可变 wait_until 且尚无分录为 WAITING。不会用当前 match_status、支付状态、批次状态或账户缓存余额重放。到期但 worker 尚未处理的 WAITING 仍按当时真实状态报告，不虚构应用分录。
- BANK 重报范围内的 `DEPOSIT_AMOUNT_MISMATCH` / `UNMATCHED_BANK_LINE` 时采用入口相同 `(category, bank_txn, bank_txn_id)`。发现照常插入、关联原 INGESTION issue，不新建 issue、不覆盖其内容与人工处置、不自动 EXPLAINED。2105 未清项另以每笔 bank_txn 报 `UNAPPLIED_CASH_OPEN`。
- AGING 只对 `HELD(UNKNOWN_PAYMENT)` 的缺失支付逐笔检查，起点为批次 occurred_at；同一支付出现在多个 HELD 批次时合并为同一识别键，取仍在等待的最早批次计算到期，保留各批次和 fetch 引用。新历史固定原因和明细 fetch；旧历史缺字段不回填、不猜当前值。相关完整 fetch 的编号（`processor_fetch:<id>`）和哈希也存入运行输入。
- AGING `summary.limitations` 固定说明“老化只能发现有开始记录、没有结束记录的义务”“其他 HELD 原因由入口差异负责，等 P4 纠错后重核”，并说明旧 HELD 历史缺字段的覆盖边界。PARKED 的 P3 T01 覆盖及回填限制见上节；退款、提现分别留给 P3/P5。
- `POST /api/recon/runs` 接受 `kind=ALL`：在同一 REPEATABLE READ 事务内顺序建立 PROCESSOR、BANK、AGING 三个运行，两个时点相同，全部成功才提交。返回 `{runIds:[处理方运行号,银行运行号,老化运行号],runs:[三个报告]}`；dry run 的 runIds 为空、报告 id 为 null，完全不写库。单种类响应保持单个报告。
- T11 的独立纯 Java 参考模型用 jqwik 状态动作组合确认、银行导入（含重复）、推进到期、WAITING 扫描、重跑；种子 2026102311，每序列最多 12 步，本地 100 条、CI 1000 条。每步比较逐笔发现集合、重放和账本不变；不替代并发测试。

### P2 T16 实现备注：三进程冒烟时间

- `scripts/smoke-p2.sh` 通过真实模拟器控制接口执行 `sample-a-1-3` 的金额与动作；不使用会回滚且不投递 webhook 的场景导出接口。冒烟业务日期固定为 2026-10-19～22，cutoff 为 10-22 多伦多午夜（04:00Z），让主应用的真实注入 Clock 能验证截止与证据时点；运行机器时间须晚于该截止。
- 模拟器业务时钟独立快进，签名仍取既有实时签名 Clock。全部资金处理、银行导入和处理方快照完成后，以主应用 `/api/overview` 的 `asOf.at` 之后第一个整秒为 evidence_as_of（避免 Instant 纳秒与 PostgreSQL 微秒精度造成报告字符串差异），再轮询主应用时钟直到满足默认 60 秒证据宽限。没有新增生产时钟开关、缩短宽限或改动业务时间契约。

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

### P3 T05 实现备注：退款 HTTP 与单事务幂等

- `POST /api/refunds` 接收 `payment_ref`（`ch_` 加非空 ASCII 字母／数字／下划线）、`amount`（1～99999999 的 JSON 整数）、`merchant_refund_ref`（非空白、最多 64 个 Unicode 码点）；成功 201。响应为 `refund_id/status/amount/payment_ref/merchant_refund_ref/accepted_at`。按数据字典 T04 约定，响应 `payment_ref` 是本地 `pay_…`，原样复用 INSERT 时已永久保存的 `initial_result`，不尝试 UPDATE 此列。
- `POST /api/refunds/{rfd_id}/cancel` 接收非空白 `reason`，成功 200，响应为 `refund_id/status/amount`；必须是合法 `rfd_` 加 ULID。重复取消用同一幂等键返回首次取消结果；新键取消已取消或 READY 之后的退款返回 409。不存在的退款返回 404 `REFUND_NOT_FOUND`。
- 两个写接口均要求 `Idempotency-Key` 为 1～255 个可见 ASCII 字符（0x21～0x7e，不含空格）。P3 无认证：`client_id` 只取配置 `ledgerlab.api.client-id`（默认 `demo-merchant`），不读客户端身份请求头；P7 部署时换为认证主体。
- JSON 必须为单一对象，拒绝未知／重复键、缺失或类型不符字段、字符串中的 NUL、尾随 JSON。金额检查 JSON token，仅整数 token 才直接读取 long；小数、指数、字符串、null、缺失及溢出均为 INVALID_AMOUNT，不先转换成 double。指纹为 UTF-8 的 `SHA-256("POST" + 规范路由路径 + 规范 JSON)`；JSON 键排序、无空白，字段值不 trim、不改大小写，整数与字符串转义按解析值规范化。
- **单事务**：设置事务局部 lock_timeout → `lockFunds()` → INSERT IN_PROGRESS（冲突不插入）→ 相同键比较指纹／读取原结果 → 检查永久业务编号 → T04 受理／取消 → 保存 COMPLETED 及响应 → 提交。受理的 `initial_result` 已由 T04 在 INSERT 写入；API 首次与重放均按键排序序列化保存的 body，状态码与响应文本一致。业务编号同金额直接返回原受理结果，不重跑当前状态／额度检查；不同金额 BUSINESS_REF_CONFLICT。
- 业务 4xx 及任何异常均回滚本事务，不留下幂等记录、退款、posting 或命令；与 Stripe 缓存部分 4xx 的策略不同。失败后同键请求重新判断。IN_PROGRESS 不以已提交状态出现，列保留给 P5 长调用；防御性读取到它时返回 409，不接管／覆盖。
- 同键并发后到者等待资金锁／唯一索引；先到者提交后返回已完成原结果，仍在处理且等待超时返回 409（AC-2.12 允许两者）。`ledgerlab.api.idempotency-lock-timeout` 默认 5s，至少 1ms、至多 PostgreSQL 支持的 2147483647ms；55P03 锁超时映射为 IDEMPOTENCY_REQUEST_IN_PROGRESS，其他数据库故障仍向外抛出并回滚。
- `expires_at` 按数据字典在 INSERT 时设为注入 Clock +24h，不 UPDATE 未授权列。定时每分钟清一批 `expires_at ≤ Clock.instant()` 的缓存；配置 `idempotency-cleanup-interval`、`idempotency-cleanup-batch-size`（默认 500，正数）、`idempotency-cleanup-enabled`（默认跟随 inbox 调度开关），均在 `ledgerlab.api` 下。清理同样先取资金锁，避免在 API 冲突判断与读取之间删除缓存；随后按到期时间／联合主键排序，FOR UPDATE SKIP LOCKED 批量删除。未清理的到期行仍可返回缓存结果，永久业务编号不被清理。
- `GET /api/refunds/{id}` 通过 investigation readonly 数据源在单个 REPEATABLE READ 快照返回 `{refund, history, commands, asOf}`：当前退款、按历史 id 排序的状态变化、脱敏命令摘要。GET 不改变业务或缓存；找不到退款返回 404。命令视图与字段见数据字典 V16 备注。
- 退款业务错误使用 Spring HTTP 错误状态和 ProblemDetail JSON（`type/title/status/detail/instance`），额外包含稳定的 `code`；不返回底层 SQL／异常正文。错误码如下：

| HTTP | code | 条件 |
|---|---|---|
| 400 | IDEMPOTENCY_KEY_REQUIRED | 缺少或非法幂等键 |
| 400 | INVALID_AMOUNT | 金额缺失、不是 JSON 整数或不在范围 |
| 400 | INVALID_REQUEST | 其他字段／JSON／退款编号格式错误 |
| 404 | PAYMENT_NOT_FOUND / REFUND_NOT_FOUND | 支付／待取消退款不存在 |
| 409 | REFUND_EXCEEDS_REMAINING | 超过支付剩余可退额 |
| 409 | PAYMENT_NOT_REFUNDABLE | 支付已 VOIDED |
| 409 | REFUND_NOT_CANCELLABLE | 退款不在 AWAITING_FUNDS |
| 409 | BUSINESS_REF_CONFLICT | 永久业务编号相同、金额不同 |
| 409 | IDEMPOTENCY_REQUEST_IN_PROGRESS | 锁等待超时或读取到预留的 IN_PROGRESS 状态 |
| 422 | IDEMPOTENCY_KEY_REUSED | 同一客户端的幂等键对应不同方法／路径／规范请求体 |

- 测试以真实 PostgreSQL 和 MockMvc 覆盖全部写接口场景，另以随机端口 Spring Boot 与 JDK HTTP 客户端验证配置绑定、受理／重放、只读查询和取消的完整装配。包内构造器钩子分别在退款插入后、幂等记录完成但提交前、提交成功且 HTTP 响应写出前抛异常；生产装配为无操作，没有运行时故障开关。最后一种用新连接确认提交可见后抛异常，再同键重试，验证 AC-4.7；不将此测试称为真实网络断线测试。

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

### P2 T10 实现备注：处理方快照与运行

- `POST /api/recon/processor-snapshots` 接受 `{"businessDate":"2026-11-10"}`。按日历的当地两个午夜计算 UTC 半开区间，23/25 小时日照常处理；拉完 payout 再拉每个 payout 的全部明细，取注入 Clock 的完成时刻。返回不可变的 `fileId/status/rejectReason`，完整或拒绝均 HTTP 200；日期参数错误 400。
- 夹具入口为 `ProcessorSnapshots.importSnapshot(sourceName, businessDate, payoutsJson, linesJsonByPayout, fetchedAt)`。两类 JSON 均为 Stripe 列表信封，调用者把完整分页合到 data，has_more=false；true、缺少某 payout 明细、额外 payout、非法行或日期范围不符都拒绝整份。payout 转账自身不形成明细；charge/refund/payout_failure 分别保存为 PAYMENT/REFUND/PAYOUT_FAILURE，memo 缺省为空。原始夹具包括跳过的转账行仍存档。
- 规范化对对象键和 payout/行集合排序，保留重复次数。新文件按行身份比较完整业务字段（含 batch、日期、memo），变化开 STATEMENT_VERSION_CONFLICT，旧证据不改。最新行版本按 fetched_at、recorded_at、file_id 降序取其所属快照的全部重复行；新快照原样出现同一行不算跨文件重复。单一快照内同身份不同内容也保留并报版本冲突；批次合计选规范排序后的首行，给出该冲突供调查。
- PROCESSOR HTTP 运行使用固定 `processor-v1`，其他种类沿用配置版本。支付范围为请款 occurred_at < cutoff_at；状态取 evidence_as_of 前最后一条 transition，实际费与批次关联取此前的不可变 settlement_batch_line。批次净额仅在 evidence_as_of 前已有 CONFIRMED 历史时使用，证据选择不使用当前可变支付状态。
- 已结算支付只有在其批次有完整快照时才检查 MISSING_IN_STATEMENT；未结算且无快照行的支付，未到期报 TIMING_DIFFERENCE，到期留给 T11。未知本地支付的行按该行 occurred_at 判断范围。每条发现携带文件/行/批次/支付及已有分录引用；后续匹配行进入 checkedScope，驱动旧时间差转 EXPLAINED，解释包含该批次及行。
- P2 报告 limitations 明示不处理已退款支付/REFUND 行；含 REFUND 的批次不做部分总额比较，留给 P3。过期未结算义务由 AGING 检查。模型测试增加完整快照、重复行、费用变更、分页中断和重跑动作；独立纯 Java 参考模型，不复用业务规则。固定种子 2026102310，每序列最多 12 步，本地 100 条、CI 跟随既有 1000 条设置；每步比较档案数、明细数、冲突数、发现集合和账本不变。

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
