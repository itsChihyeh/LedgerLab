# 资金模型与手算样例

P0 设计稿 v0.2，2026-10-21（同日开工前审查修订，见第 8 节）。取代立项讨论稿 2.1a 的分录草案。依据：立项阶段的已定决策和 2026-10-21 立项复核（S1–S4 严重问题、I1–I7 重要问题；复核记录不在本仓库）。

本文是“钱在哪里、怎么动”的唯一正本。状态机见 [state-machines.md](state-machines.md)，时间与编号见 [contracts.md](contracts.md)，表结构见 [data-dictionary.md](data-dictionary.md)。

---

## 1. 设定

- 我们是一家小型支付平台，替**一个**商户收款，币种 CAD，金额一律用整数分（`BIGINT`）；单笔支付 50 分～999,999.99 元（`99999999` 分，Stripe 单笔金额最多 8 位），超出范围的事件拒绝。退款、提现的金额范围见 [contracts.md](contracts.md) 第 3 节。
- 上游**处理方**（Processor）负责扣卡、执行退款，每个结算批次按**净额**打款到我们的**银行账户**。处理方就是 Stripe：真实运行时接 Stripe 测试模式，测试和故障注入时接说 Stripe 协议子集的模拟器（ADR-0016）。一个“批次”就是一笔 Stripe payout。
- 批次净额 = 本批支付毛额 − 本批手续费 − 本批已执行退款 + 本批退回的失败打款。
- Stripe 的卡退款只用处理方的**可用余额**，不够时退款停在 `pending`，等后续收入变成可用再执行，所以正常流程里处理方余额不会因退款变负。处理方余额真的变负（例如拒付）时，Stripe 会建一笔**从我们银行账户扣款的负 payout**。这是**首版不支持的范围**：首版只处理净额 > 0 的标准自动 payout，其他 payout 在入口隔离（批次 `HELD(UNSUPPORTED_PAYOUT)`，开差异），模拟银行也不会把它当成收入。
- 手续费由处理方扣，原样转嫁给商户，平台不加价、没有收入账户。合同费率 `fee = 30 + round_half_up(gross × 2.9%)`，即 `30 + (gross × 29 + 500) div 1000`。退款不退手续费。
- **三份证据**：处理方 webhook（驱动记账）、处理方的结算数据（按截止时点拉取的 balance transactions 和 payouts 快照，对账用）、银行流水 CSV（对账用，也是现金入账的唯一依据）。前两者都来自处理方，只是渠道不同（推送 vs 拉取）；银行流水来自另一方。
- 手续费有三个数，不能合成一个字段：处理方事件报告的实际手续费（记账用）、结算单上的手续费（对账用）、按合同算的预期手续费（`FEE_MISMATCH` 用）。
- 不做：多商户、多币种、拒付、授权与请款分开（我们只看到已请款的支付）、单笔多次请款或部分结算、批次拆分到账的自动应用、平台收入。遇到不支持的事件类型（例如拒付）或批次行类型，一律隔离：事件进 `REJECTED(UNSUPPORTED)`，批次进 `HELD` 并显示为不完整，绝不当成功处理。

## 2. 账户表

| 代码 | 账户 | 类型 | 正常方向 | 可为负 | 含义 |
|---|---|---|---|---|---|
| 1101 | `processor_receivable` 处理方应收 | 资产 | 借 | **可以** | 处理方欠我们的净额：已请款支付 − 已执行退款 − 已结算部分。正常流程里不为负（Stripe 退款要等可用余额）；只有首版不支持的场景（拒付、处理方授信）才会为负，这时显示为“处理方净应付”，由下一个批次扣回 |
| 1102 | `settlement_in_transit` 结算在途 | 资产 | 借 | 否 | 处理方已确认、银行流水还没出现的批次净额 |
| 1103 | `bank_cash` 银行现金 | 资产 | 借 | **否（硬约束）** | 银行流水证实的我们账户里的钱 |
| 2101 | `merchant_pending` 商户待结算 | 负债 | 贷 | 否 | 已请款、还没进入已确认批次的支付毛额 |
| 2102 | `merchant_available` 商户可用 | 负债 | 贷 | **可以** | 商户已结算的钱。为负时页面显示为“商户欠款 X”，不显示“可提现 −X” |
| 2103 | `refund_payable` 退款待付 | 负债 | 贷 | 否 | 已受理、处理方还没执行的退款，欠顾客的钱 |
| 2104 | `payout_in_transit` 提现在途 | 负债 | 贷 | 否 | 已为提现预留、银行流水还没证实扣款的钱 |
| 2105 | `unapplied_cash` 待查收款 | 负债 | 贷 | 否 | 银行流水里有、但精确匹配不到任何批次或提现的入账（例如处理方重复打款、拆分到账、不认识的来款）。让账上现金始终等于银行流水，同时把“这笔钱是谁的”留成一个未结项 |

- 账户代码就是**加锁顺序**：一个事务要锁多个账户时，按代码升序加锁（ADR-0004）。
- 余额按正常方向计：资产 = Σ借 − Σ贷，负债 = Σ贷 − Σ借。posting 金额恒为正，方向用 `D`/`C` 表示。
- 立项稿里的 `processor_payable`（处理方应付）取消：退款受理时欠的是顾客（`refund_payable`），处理方执行以后才变成应收的减少。

## 3. 分录目录

每个分录有一个 `entry_type`（即 reason code）和一个全局唯一的 `source_key`，同一业务事实最多记一次（INV-8）。

| entry_type | 触发（证据） | postings | source_key |
|---|---|---|---|
| `PAYMENT_CAPTURED` | 处理方 webhook `charge.succeeded`，且通过请款准入检查（[stripe-integration.md](stripe-integration.md) 第 2 节：已全额请款、CAD、测试模式） | 借 1101 G / 贷 2101 G | `payment:{processor_payment_ref}:captured` |
| `BATCH_SETTLED` | payout 明细就绪（webhook `payout.reconciliation_completed`，或查询到 `reconciliation_status=completed`）后，适配器拉取该 payout 的全部 balance transactions 作为批次行，且全部能对上本地记录 | 借 1102 N、借 2101 F / 贷 1101 N+F；借 2101 G−F / 贷 2102 G−F；**金额为 0 的 posting 省略** | `batch:{batch_id}:settled` |
| `BATCH_PAYOUT_FAILED` | 处理方 webhook `payout.failed` | 借 1101 N / 贷 1102 N | `batch:{batch_id}:payout_failed` |
| `BANK_DEPOSIT_RECEIVED` | 银行流水入账行，按批次号**精确**匹配到已确认批次且金额相等 | 借 1103 N / 贷 1102 N | `bank_txn:{bank_txn_id}` |
| `REFUND_ACCEPTED` | 退款 API 受理 | 借 2102 A / 贷 2103 A | `refund:{refund_id}:accepted` |
| `REFUND_EXECUTED` | 处理方给出的任何“退款已成功”证据：出站调用的响应、`refund.created`/`refund.updated` 事件或查询结果里 `status=succeeded`，或结算批次里出现该退款行（先到者记账，后到者去重） | 借 2103 A / 贷 1101 A | `refund:{refund_id}:executed` |
| `REFUND_FAILED` | 处理方确认退款没有执行：退款对象 `status=failed` 或 `canceled`（`refund.failed` 事件、查询结果或调用响应） | 借 2103 A / 贷 2102 A | `refund:{refund_id}:failed` |
| `REFUND_CANCELLED` | 运营在提交处理方之前取消（只限 `AWAITING_FUNDS`） | 借 2103 A / 贷 2102 A | `refund:{refund_id}:cancelled` |
| `PAYOUT_RESERVED` | 提现 API 受理 | 借 2102 P / 贷 2104 P | `payout:{payout_id}:reserved` |
| `PAYOUT_PAID` | 银行流水出账行，按银行请求号精确匹配 | 借 2104 P / 贷 1103 P | `bank_txn:{bank_txn_id}` |
| `PAYOUT_FAILED` | 银行**确认**拒绝或取消，且没有任何出账流水 | 借 2104 P / 贷 2102 P | `payout:{payout_id}:failed` |
| `PAYOUT_RETURNED` | 银行流水退回行，关联原提现 | 借 1103 P / 贷 2102 P | `bank_txn:{bank_txn_id}` |
| `UNAPPLIED_CASH_RECEIVED` | 银行流水入账行精确匹配失败（引用不认识、金额不等、同一批次第二笔入账） | 借 1103 X / 贷 2105 X | `bank_txn:{bank_txn_id}` |
| `UNAPPLIED_CASH_RETURNED` | 银行流水出账行，明确引用一笔待查收款的流水编号（例如把重复打款退还处理方） | 借 2105 X / 贷 1103 X | `bank_txn:{bank_txn_id}` |
| `REVERSAL` | 受限纠错（ADR-0011） | 原分录每条 posting 方向取反 | `reversal:{original_entry_id}` |
| `CORRECTION` | 受限纠错，紧跟一条 `REVERSAL` | 正确的 postings | `correction:{correction_id}` |

说明：

- `BATCH_SETTLED` 是**一个分录**，按上表最多 5 条 posting，**金额为 0 的 posting 不写**，`posting_count` 按实际条数封存（ADR-0015 补充）。普通支付批次是 5 条；只有失败打款退回的批次（G = F = 0）只有“借 1102 N / 贷 1101 N”两条（样例 H）。手续费那条“借 2101 F”的意思是：商户待结算款里有 F 被处理方扣走了。
- 任何一行的实际手续费大于该行毛额（G − F 会为负）：批次 `HELD(FEE_EXCEEDS_GROSS)`，不记账。
- 批次分录贷记 1101 的金额是 N+F，而不是本批支付毛额 G，因为本批退款和退回的失败打款早已分别记进 1101。记账前要先核对：本批每一行支付毛额、手续费、每一行退款、每一行失败打款退回，都必须和本地记录**逐项相等**；每笔支付和退款只能出现在一个批次里（零容差）；任何一项对不上，批次进入 `HELD`，不记账，开对账差异（ADR-0010）。
- **银行入账行的匹配条件**（2026-10-23 定，P2 开工前置 ②）：一行银行流水能记 `BANK_DEPOSIT_RECEIVED`，当且仅当**全部**成立：`status = BOOKED`；`direction = CREDIT`；`currency = CAD`；`reference` 等于某个批次的 `batch_id`；该批次状态是 `CONFIRMED`；金额等于批次 `net`；该批次还没有别的入账行匹配过。任何一项不等都不算匹配，按下面的规则处理，绝不“差不多就配上”。
  - `status = PENDING`（银行还没记账）的行只存档，不记账、不参与匹配。流水证据身份为 `(bank_txn_id, version)`，当前有效版本为最高版本（行版本独立于按报表日期计数的文件版本）；同一 `bank_txn_id` 只有 PENDING→BOOKED 且其余内容（包括方向、金额、币种、reference、value_date）不变，才接受为状态前进，两份证据均保留，只有 BOOKED 参与匹配和记账。其他内容变化或 BOOKED→PENDING 均为 `STATEMENT_VERSION_CONFLICT`，旧行不动。
  - **银行证据早到**：`reference` 形如 `po_…`，但对应批次还不存在、或还在 `ANNOUNCED`/`RECEIVED`/`HELD`：行进入 `WAITING`，不记账。批次进入 `CONFIRMED` 的同一事务里重新匹配所有引用它的 `WAITING` 行（就像 inbox 的 `PARKED` 被唤醒）；后台也按导入顺序定期重扫。等待从 `booking_date` 起最多 2 个工作日（`ledgerlab.bank.wait-business-days`），到期仍匹配不上就按“精确匹配失败”记待查收款。等待期间账上现金比银行少，银行余额核对把恰好等于全部 `WAITING` 行合计的差额报为 `TIMING_DIFFERENCE`，不是 `BANK_BALANCE_MISMATCH`。
  - CAD 入账行精确匹配失败（引用不认识且不是 `po_…`、批次已 `CONFIRMED` 但金额不等、批次已 `BANKED` 又来一笔、批次已 `PAYOUT_FAILED`、等待到期）：记 `UNAPPLIED_CASH_RECEIVED`，让账上现金继续等于银行；开差异：金额不等是 `DEPOSIT_AMOUNT_MISMATCH`，批次已 `PAYOUT_FAILED` 是 `BATCH_STATE_CONFLICT`，其余是 `UNMATCHED_BANK_LINE`；能看出相关批次的都把它列为候选。
  - P2 没有提现：BOOKED 出账行（`DEBIT`）和非 CAD 行一律不记账，`match_status = UNMATCHED_NO_POSTING`，开 `UNMATCHED_BANK_LINE`；非 CAD 金额不能作为 CAD 分记入账户，差额由 T11 银行余额核对暴露。`PAYOUT_PAID`、`PAYOUT_RETURNED`、`UNAPPLIED_CASH_RETURNED` 在 P5 实现。
- 待查收款只能通过受限纠错（ADR-0011）清出：例如确认是处理方重复打款并已退回，或确认是某批次的拆分到账。首版不自动应用，任何未结的 2105 余额都是一条对账差异。
- 银行流水出账行匹配不到提现时**不记账**，只开差异 `UNMATCHED_BANK_LINE`；这时账上现金和银行余额不等，会被 INV-11 抓到。
- 提现失败和退款失败都是**业务事件**，用各自的 entry_type，不用 `REVERSAL`。`REVERSAL` 只用于纠正记错的账。
- 处理方说打款失败（Stripe `payout.failed`）时，钱回到处理方余额：记 `BATCH_PAYOUT_FAILED`（借 1101 N / 贷 1102 N）。退回的钱会作为一行 `PAYOUT_FAILURE` 出现在之后的某个批次里。
- 处理方从我们银行账户扣款（Stripe 的负 payout）首版不支持：payout 在入口隔离；如果扣款流水已经出现在银行流水里，按 `UNMATCHED_BANK_LINE` 处理。

### P2 T08 实现备注：银行导入与现金应用

- `settlement.BankStatements.importBankStatement` 先按 contracts 4.1 完整解析并校验 UTF-8 CSV（含双引号转义和字段内换行），再用一个事务处理整文件：先 `Ledger.lockFunds()`，随后检查 SHA、归档、按 CSV 顺序合并和匹配；文件、差异、分录、余额、批次历史一起提交或回滚。选择整文件事务便于重试时保持原导入结果一致；大文件会延长单商户资金锁的持有时间。
- 行版本从 1 递增，比较全部银行事实字段，包括 booking_date、value_date 和 memo；只有 status 的 PENDING→BOOKED 可前进。不同文件版本重复同一行不再插入；冲突文件仍 ACCEPTED，原行不覆盖，冲突证据和新文件原文由 INGESTION 差异关联。
- 匹配先排除 PENDING、DEBIT、非 CAD；PENDING 只存档，BOOKED 的 DEBIT 或非 CAD 行存为 UNMATCHED_NO_POSTING、开 UNMATCHED_BANK_LINE，不写分录或改变现金，不做外汇转换（T09b 修复）。CSV 尾合计仍按外部原数值校验，账本与银行的差额留给 T11 银行余额核对。CAD 入账行在批次已确认且未匹配时才检查金额；失败批次开 BATCH_STATE_CONFLICT，已 BANKED 的第二笔开 UNMATCHED_BANK_LINE。已知候选保存在 evidence_refs。
- `wait_until` 按 booking_date 的多伦多 00:00 调 T01 `dueAt` 计算，在插入 WAITING 时固定；配置变更只影响新等待行。`BatchSettlement` 在 BATCH_SETTLED 之后同步唤醒引用该批次的等待行，覆盖 webhook、轮询确认和 HELD 重核；按 import_order 处理，同事务内第一笔精确匹配后批次 BANKED，后续真实入账转待查收款。到期扫描也先取资金锁，按 import_order 处理；`ledgerlab.bank.poll-interval` 默认 1m，`scheduling-enabled` 默认继承 inbox 调度开关，测试关闭调度并直接调用 `expireWaiting()`。
- 银行分录 occurred_at 使用 booking_date 的多伦多 00:00；received_at 是调用导入时注入 Clock 的时刻，recorded_at 是资金锁内处理时刻。日期型证据不伪造更细粒度的银行业务时间。
- AC-1.4b 的样例 A 第 2′ 步在测试里按 4.2 公式只读计算 free_cash=0，不引入 P5 提现 API。定向测试覆盖 AC-5.4/5.5/5.6/5.10/5.12 文件部分及 AC-5.20 入账部分；银行余额对账的 TIMING_DIFFERENCE 留给后续对账任务。
- jqwik 独立参考模型增加正常入账、第二笔、PENDING、短款、外币、出账和等待到期动作，与已有确认、失败、重打动作混排；种子 `2026102308`，最多 24 步，本地 100 条序列、CI 1000 条。每步核对全部余额、银行行版本与匹配状态、分录数量、差异身份及账本不变量；不替代栅栏并发与受控回滚测试。

## 4. 两个门槛

### 4.1 退款执行门槛（S3）

1. **受理**只检查这笔支付还能退多少：`captured − Σ(未失败、未取消的退款) ≥ A`。通过就记 `REFUND_ACCEPTED`，即使商户可用因此变负。
2. **执行**（向处理方发出退款命令）要求资金覆盖。等待队列按受理顺序排列，放行**最长前缀**，使
   `Σ 前缀金额 ≤ merchant_available + Σ 全部等待中的金额`。
   直观地说：假设只有这些退款占用了资金，商户可用仍然 ≥ 0。
3. 不满足的退款停在 `AWAITING_FUNDS`，每次 2102 增加（批次结算、提现失败或退回、退款失败）之后重新计算一次。
4. 银行现金不会因为退款变负：退款由处理方执行并从批次净额里扣，我们从不直接用银行现金付退款。
5. 本地放行（`READY`）只说明商户的钱够；处理方那边还要有可用余额。不够时 Stripe 返回 `pending`，本地记为 `SUBMITTED`，等处理方真正执行（样例 A 第 16～18′ 步）。fake Stripe 按同样的规则维护可用余额和待结算余额。

只用 `merchant_available` 作覆盖来源，不算 `merchant_pending`，是保守取舍：待结算款的手续费还没确定。代价是“刚收款就退款”的情况要等到批次结算才执行（ADR-0005）。

### P3 T04 实现备注：受理、FIFO 与取消

- T04 的 `refunds` 模块依赖 `ledger`、`payments`、`shared`；T07 增加 outbound SPI 与 reconciliation 的公开差异入口。受理先取资金锁，再通过 `PaymentRefunds` 锁支付行并检查剩余可退额；支付额度增加、退款插入、历史、`REFUND_ACCEPTED`、队列放行与命令落库同事务完成。CAPTURED 和 SETTLED 均可受理，VOIDED 拒绝；金额允许 1～99999999 分。
- 新退款先作为 AWAITING_FUNDS 加入集合；`RefundQueue.release()` 只检查调用事务已持有资金锁，不主动取锁。等待退款先按 id 锁行，再按 `queue_seq` 求最长可覆盖前缀，合计用 `Math.addExact`，遇到第一个不能覆盖的退款即停止。READY 与 SUBMIT_REFUND 命令同事务创建，重复调用不会重复建命令。
- `BatchSettlement` 在 BATCH_SETTLED 之后同步调用 release，然后唤醒银行等待行（P3 T10 调整顺序）；取消只允许 AWAITING_FUNDS 且必须有原因，恢复支付额度、记 REFUND_CANCELLED 后同步放行。同事务回滚也撤销队列状态与命令。退款失败的接线由 T07 的 RefundEvidence 完成，提现失败／退回留 P5。
- 公开接口只新增 `Ledger.assertFundsLocked()`（复用事务持锁标记）和 `payments.PaymentRefunds` 的窄支付行接口。settlement 模块允许依赖列表仅增加 refunds；不改变其他模块边界。
- AC-3.13 通过包内可替换的额度协作者验证丢失更新：故障实现只存在于测试，生产没有去锁开关。真实 PostgreSQL 的资金分录与退款额度不变量检查见 [T04 测试说明](../testing/refund-acceptance.md)。

### P3 T10 实现备注：批次退款行

- 先核对全批明细，再对 READY／SUBMITTED／UNKNOWN 的退款通过统一 `RefundEvidence` 入口应用成功证据；来源 BATCH_LINE，证据号为 balance transaction 的 txn_，occurred_at 取该行 created。已 SUCCEEDED 不重复执行。退款执行、批次确认与分录、退款 batch_id、FIFO 放行、银行 WAITING 唤醒同事务提交或回滚；外部拉取存档独立保留。
- 每笔退款按 re_ 匹配；退款行 gross 为负，refunds 合计取其相反数。BATCH_SETTLED 仍贷 1101 的 N+F，不再次扣退款；普通支付加退款的批次仍为五条 posting。fee 沿用数据字典的非负整数范围；退款行若有非零 fee，也计入本批 F，不改变退款本金或原支付手续费；本批总手续费超过支付毛额时 HELD(FEE_EXCEEDS_GROSS)，避免产生负的 G−F posting。
- 定向测试覆盖样例 A 第 7、19 步（第 9～11 步提现只用账本夹具建立前置余额，不实现 P5）、两笔部分退款、失败／等待／取消／金额冲突、跨批次复用、HTTP 重试、并发去重和事务回滚。每例检查 INV-1～5、INV-9 的 2101／1102／2103 及 INV-10。退款 jqwik 模型增加 REFUND_BATCH 动作，独立计算执行额、入批额和净在途金额，保留种子 2026102407 与最多 12 步、本地 100／CI 1000 条序列。

### 4.2 提现门槛（S3）

同一事务里，按账户代码升序锁住 1101、1103、2102、2104、2105，然后要求：

- `P ≤ merchant_available`（商户余额）；并且
- `P ≤ free_cash`，其中 `free_cash = bank_cash − payout_in_transit − unapplied_cash − max(0, −processor_receivable)`（没被占用的流动资金：已到账的现金，减去已预留的提现、来历未查清的收款，再减去欠处理方、要由下一批扣回的钱）。

任何一条不满足，返回 `409 INSUFFICIENT_FUNDS`（带具体原因 `MERCHANT_BALANCE` 或 `FREE_CASH`），不记账、不建提现单。第二条的作用是：批次“已确认”但银行流水还没到时，钱不能先提走（S2）。

## 5. 不变量

| # | 不变量 | 怎么守 |
|---|---|---|
| INV-1 | 每个分录 Σ借 = Σ贷，至少 2 条 posting，每条金额 > 0 的整数分 | 数据库延迟约束触发器（提交时检查）＋ CHECK |
| INV-2 | 账户行上的余额 = 从 postings 重算的余额 | 同事务更新；测试与定时任务在同一个快照（`REPEATABLE READ` 事务）里重算核对，避免并发写入造成假差异 |
| INV-3 | 全部资产 = 全部负债 | INV-1 的推论，仍然单独检查 |
| INV-4 | 分录封存：头上的 `posting_count`、`total_amount` 等于实际集合；提交以后不能再往里加 posting | 触发器：posting 只能在创建该分录的同一事务里插入；提交时核对条数和合计（S4） |
| INV-5 | 1102、1103、2101、2103、2104、2105 不为负 | 记账时检查，违反则整个事务回滚；`ledger.account` 上的 CHECK 约束兜底，直连数据库改余额也会被拒绝 |
| INV-6 | 2102 只会因 `REFUND_ACCEPTED` 或 `CORRECTION` 变负；`PAYOUT_RESERVED` 之后 2102 ≥ 0，且提现金额 ≤ 当时的 `free_cash` | 4.2 节门槛；模型测试检查 |
| INV-7 | 每笔支付：Σ(未失败、未取消的退款) ≤ 请款金额 | 锁支付行后检查 |
| INV-8 | 同一 `source_key` 最多一个分录 | 唯一约束 |
| INV-9 | 业务状态与过渡账户一致：2103 余额 = Σ 处于 `AWAITING_FUNDS`/`READY`/`SUBMITTED`/`UNKNOWN` 的退款金额；2104 余额 = Σ 处于 `RESERVED`/`SUBMITTED`/`UNKNOWN`/`ACCEPTED` 的提现金额；2101 余额 = Σ 状态为 `CAPTURED` 的支付毛额（已撤销的 `VOIDED` 不算）；1102 余额 = Σ `CONFIRMED` 批次净额 | 模型测试每一步检查；对账老化检查逐笔核对 |
| INV-10 | 资金效果唯一：每个退款、提现最多被预留一次，并且最多被结清或释放一次（执行/失败/取消三选一；出账/失败二选一）；结果未知时不释放 | 状态机＋`source_key`；模型测试 |
| INV-11 | 银行侧核对：每份银行流水 期初 + Σ入账 − Σ出账 = 期末；截至流水期末，账上 1103 = 银行期末余额，否则差额必须作为差异显示 | 对账运行 |

### P2 T15 实现备注：应用级独立模型

`PaymentModelProperties` 已在原 P1 动作链中加入批次、分页中断、失败重打、银行文件及 WAITING 到期。`PaymentModel`／`SettlementModel` 只用 JDK 独立计算，逐步比较全部账户增量、批次 posting 多重集合（省略 0 元）、状态和流水匹配，并检查全库 INV-9 的 2101／1102 与 INV-11 的“唯一 BOOKED 入账合计减 WAITING”。生成银行证据限定为 CAD CREDIT，包含待查收款；外币、出账及完整期间对账仍由专项测试负责。批次生成净额不超过单行银行／退回行的 99999999 上限，这是测试生成域，不改变汇总金额契约。每条场景独立设置起始 Clock，场景内只向前推进，避免共享测试库的 1000 条序列累计超出日历年份。日志重放原始处理方 HTTP 分页和银行文件，空库比较扩展到批次、明细、流水版本和差异状态。动作、边界、收缩演示与实测见 [model-tests.md](../testing/model-tests.md)。

### P3 T13 实现备注：应用级模型与手算样例

`PaymentModelProperties` 在既有动作链中加入退款 API、取消、出站回执、退款 webhook、过期恢复及带退款行批次；`RefundModel` 仅依赖 JDK 和其他独立模型，逐步核对额度、FIFO、2102/2103/1101、一次性退款资金效果和不触碰 1103。完整输入日志保存 API、回执（含超时）、时钟和早到 webhook，空库比较扩展到退款历史、命令终态和幂等结果。动作边界和实测见 [model-tests.md](../testing/model-tests.md)。

样例 A 第 1–8 步及 D 通过真实应用入口逐格核对余额与 posting。D 未指定初始资产分布，测试用请款 5180、手续费 180、批次与银行入账建立 CASH=MA=5000 的前置；不新增设计账户或直接 SQL 造账。两例同时提供独立模拟器场景与真值，见 [scenario-fixtures.md](../testing/scenario-fixtures.md)。

## 6. 手算样例

符号：应收 PR(1101)、结算在途 SIT(1102)、银行现金 CASH(1103)、待结算 MP(2101)、可用 MA(2102)、退款待付 RP(2103)、提现在途 PIT(2104)。资产 = PR+SIT+CASH，负债 = MP+MA+RP+PIT。手续费：10000→320，5000→175，4000→146。

### 样例 A：主线（批次净额、退款批内抵扣、提现结果未知、资金不足等待、处理方可用余额不足时退款 pending）

| # | 事件 | 分录（借 / 贷） | PR | SIT | CASH | MP | MA | RP | PIT | 资产=负债 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | P1 请款 10000 | 借 PR 10000 / 贷 MP 10000 | 10000 | 0 | 0 | 10000 | 0 | 0 | 0 | 10000 |
| 2 | 批次 B1 确认：P1 毛额 10000、费 320，净额 9680 | 借 SIT 9680、借 MP 320 / 贷 PR 10000；借 MP 9680 / 贷 MA 9680 | 0 | 9680 | 0 | 0 | 9680 | 0 | 0 | 9680 |
| 2′ | 此时申请提现 9680 | 拒绝：`free_cash` = 0（钱还在途） | | | | | | | | |
| 3 | 银行流水：B1 入账 9680 | 借 CASH 9680 / 贷 SIT 9680 | 0 | 0 | 9680 | 0 | 9680 | 0 | 0 | 9680 |
| 4 | P2 请款 5000 | 借 PR 5000 / 贷 MP 5000 | 5000 | 0 | 9680 | 5000 | 9680 | 0 | 0 | 14680 |
| 5 | 受理退款 R1 2000（退 P1）；MA 足够，放行 → 发出退款命令 | 借 MA 2000 / 贷 RP 2000 | 5000 | 0 | 9680 | 5000 | 7680 | 2000 | 0 | 14680 |
| 6 | 处理方：R1 已执行 | 借 RP 2000 / 贷 PR 2000 | 3000 | 0 | 9680 | 5000 | 7680 | 0 | 0 | 12680 |
| 7 | 批次 B2 确认：P2 5000、费 175、R1 −2000，净额 2825 | 借 SIT 2825、借 MP 175 / 贷 PR 3000；借 MP 4825 / 贷 MA 4825 | 0 | 2825 | 9680 | 0 | 12505 | 0 | 0 | 12505 |
| 8 | 银行流水：B2 入账 2825 | 借 CASH 2825 / 贷 SIT 2825 | 0 | 0 | 12505 | 0 | 12505 | 0 | 0 | 12505 |
| 9 | 申请提现 PO1 12505（MA、free_cash 都够） | 借 MA 12505 / 贷 PIT 12505 | 0 | 0 | 12505 | 0 | 0 | 0 | 12505 | 12505 |
| 10 | 调银行超时 → `UNKNOWN`；用同一请求号重试，银行返回“已受理” | 无分录，钱继续占着 | | | | | | | | |
| 11 | 银行流水：PO1 出账 12505 | 借 PIT 12505 / 贷 CASH 12505 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 12 | 受理退款 R2 3000（退 P2）；MA 变 −3000，进入 `AWAITING_FUNDS`；页面显示“商户欠款 3000” | 借 MA 3000 / 贷 RP 3000 | 0 | 0 | 0 | 0 | −3000 | 3000 | 0 | 0 |
| 13 | P3 请款 4000 | 借 PR 4000 / 贷 MP 4000 | 4000 | 0 | 0 | 4000 | −3000 | 3000 | 0 | 4000 |
| 14 | 批次 B3 确认：P3 4000、费 146，净额 3854；之后重算队列：854 + 3000 ≥ 3000，放行 R2 | 借 SIT 3854、借 MP 146 / 贷 PR 4000；借 MP 3854 / 贷 MA 3854 | 0 | 3854 | 0 | 0 | 854 | 3000 | 0 | 3854 |
| 15 | 银行流水：B3 入账 3854 | 借 CASH 3854 / 贷 SIT 3854 | 0 | 0 | 3854 | 0 | 854 | 3000 | 0 | 3854 |
| 16 | 退款命令发出；处理方可用余额为 0（B3 已打出），Stripe 返回 R2 `pending` | 无分录；R2 `SUBMITTED` | 0 | 0 | 3854 | 0 | 854 | 3000 | 0 | 3854 |
| 17 | 次日没有新收入，不产生批次 | 无分录 | 0 | 0 | 3854 | 0 | 854 | 3000 | 0 | 3854 |
| 17′ | 此时提现上限 | MA 854；free_cash = 3854 − 0 − 0 − 0 = 3854 → 最多 854 | | | | | | | | |
| 18 | P4 请款 5000 | 借 PR 5000 / 贷 MP 5000 | 5000 | 0 | 3854 | 5000 | 854 | 3000 | 0 | 8854 |
| 18′ | P4 的资金在处理方变为可用，Stripe 执行 R2（`refund.updated` status=succeeded） | 借 RP 3000 / 贷 PR 3000 | 2000 | 0 | 3854 | 5000 | 854 | 0 | 0 | 5854 |
| 19 | 批次 B4 确认：P4 5000、费 175、R2 −3000，净额 1825 | 借 SIT 1825、借 MP 175 / 贷 PR 2000；借 MP 4825 / 贷 MA 4825 | 0 | 1825 | 3854 | 0 | 5679 | 0 | 0 | 5679 |
| 19′ | 此时提现上限 | MA 5679，但 free_cash = 3854 → 最多 3854，剩下的等 B4 到账 | | | | | | | | |

检查点：每一步资产 = 负债；CASH 从未为负，PR 也从未为负；步骤 12 商户欠款期间没有任何提现能通过；步骤 14 本地放行 R2 以后，处理方仍要等可用余额才执行（16～18′）；B2 到账 2825 = 5000 − 175 − 2000；B4 到账 1825 = 5000 − 175 − 3000。

### 样例 B：提现结果未知，后来确认失败（S1）

从样例 A 第 8 步开始（CASH 12505，MA 12505）。

| # | 事件 | 分录 | CASH | MA | PIT |
|---|---|---|---:|---:|---:|
| B1 | 申请提现 PO2 12505 | 借 MA 12505 / 贷 PIT 12505 | 12505 | 0 | 12505 |
| B2 | 调银行超时 → `UNKNOWN` | 无 | 12505 | 0 | 12505 |
| B3 | 商户再申请提现 100 | 拒绝：MA = 0 | | | |
| B4 | 查询银行：PO2 被拒（收款账户已关闭），流水里没有出账 | 借 PIT 12505 / 贷 MA 12505（`PAYOUT_FAILED`） | 12505 | 12505 | 0 |

**反例（被 S1 推翻的旧规则）**：如果 B2 超时就自动退回 MA，商户在 B3 能再提 12505；而银行其实已经付了第一笔，只是回复丢了。两笔都出账后 CASH = −12505，钱付了两次。所以 `UNKNOWN` 永不自动转失败，只有银行确认失败或取消才释放。

### 样例 C：提现已出账，几天后被银行退回

从样例 A 第 11 步开始（全部为 0）。银行流水出现一行退回 12505，原因码 `ACCOUNT_CLOSED`，关联 PO1 的银行请求号。

记 `PAYOUT_RETURNED`：借 CASH 12505 / 贷 MA 12505。PO1 状态 `PAID → RETURNED`。如果退回行找不到原提现，不记账，开差异 `UNMATCHED_BANK_LINE`。

### 样例 D：退款被处理方拒绝

MA 5000 时受理 R3 1000（MA 4000，RP 1000），提交后处理方返回失败。记 `REFUND_FAILED`：借 RP 1000 / 贷 MA 1000。R3 状态 `FAILED`，顾客那边需要运营另行处理（不在系统范围内，页面上标出）。

### 样例 E：记错账的纠正（I2）

P5 实际请款 5400，因为解析错误记成 4500（`PAYMENT_CAPTURED`：借 PR 4500 / 贷 MP 4500）。批次 B6 的支付行写的是 5400，逐项核对不通过 → B6 `HELD`，开差异 `AMOUNT_MISMATCH`，不记批次分录。

运营走受限纠错，关联差异编号和证据（结算单行、webhook 原文）：

1. `REVERSAL`：借 MP 4500 / 贷 PR 4500
2. `CORRECTION`：借 PR 5400 / 贷 MP 5400

然后重新处理 B6，核对通过、正常记 `BATCH_SETTLED`。注意：这里不能靠“给顾客退 900”来修，退款是业务事件，纠错是会计事件（AC-6）。

### 样例 F：处理方重复打款

B1 已到账（样例 A 第 3 步之后）。银行流水又出现一行入账 9680，引用也是 B1，但流水编号不同。这不是重复通知（编号不同），是第二笔真实的钱：

记 `UNAPPLIED_CASH_RECEIVED`：借 CASH 9680 / 贷 待查收款 9680；开差异 `UNMATCHED_BANK_LINE`，候选 = B1。CASH 19360 与银行余额一致；free_cash 不变（被待查收款抵掉），商户提不走这笔钱。

### 样例 G：退款与提现并发

MA 1000，CASH 1000。同时到达：退款 R4 800、提现 PO3 1000。两个事务都要锁 2102，所以必然先后执行：

- 退款先：MA 200 → 提现被拒（`MERCHANT_BALANCE`）。
- 提现先：MA 0 → 退款受理，MA −800，进入 `AWAITING_FUNDS`。

两种结果都合法；不合法的结果只有一种：两者都成功且退款被立即执行，这时平台要替商户垫 800。锁和门槛保证它不会出现。

### 样例 H：失败打款重新打出（只有失败打款退回的批次）

从样例 A 第 2 步开始（SIT 9680，MA 9680）。

| # | 事件 | 分录 | PR | SIT | MA | 资产=负债 |
|---|---|---|---:|---:|---:|---:|
| H1 | B1 打款失败（`payout.failed`） | 借 PR 9680 / 贷 SIT 9680（`BATCH_PAYOUT_FAILED`） | 9680 | 0 | 9680 | 9680 |
| H2 | 处理方重新打出这 9680：批次 B1′ 只有一行 `PAYOUT_FAILURE` +9680，G = F = R = 0，N = 9680 | 借 SIT 9680 / 贷 PR 9680（`BATCH_SETTLED`，2 条 posting，其余三条金额为 0 省略） | 0 | 9680 | 9680 | 9680 |

MA 不再增加：这笔钱在 B1 确认时已经转过可用。

## 7. 与立项稿 2.1a 的差异

### P2 T11 实现备注：INV-11 的期间与证据

- 银行余额核对选已纳入输入且 `period_end < cutoff_at` 多伦多日期的每个账户／期间最新文件版本（同版本按 recorded_at、file_id 确定顺序）。1103 从 evidence_as_of 前的 `BANK_DEPOSIT_RECEIVED` / `UNAPPLIED_CASH_RECEIVED` posting 汇总，按关联银行行 `booking_date ≤ period_end` 截止，不取当前账户余额或分录写入日期；跨日迟到入账归回其记账日。
- expected 为银行 closing_balance，actual 为账上 cash、`difference = closing_balance - cash` 和该期间 WAITING 合计；均 long 精确运算，汇总检查 JSON 安全整数范围。差额非零且恰等于期间 WAITING 合计时报 TIMING_DIFFERENCE，due_at 取其中最早 wait_until；否则报 BANK_BALANCE_MISMATCH。
- 银行报表差异 subject 为 `bank_statement`、`账户:period_start:period_end`，具体文件版本留在证据引用中；因此新版本能解释同一期间的旧差异。流水身份选择证据时点前、选定输入中的最高行版本；重复文件中的原样行沿用原行身份，不重复累计现金。未选入输入的银行文件不能解释批次，但本地账本现金仍按可见全部分录核对。

| 立项稿 | 现在 | 原因 |
|---|---|---|
| 批次结算直接借“结算现金” | 先借“结算在途”，银行流水到了才转现金 | S2 |
| 退款受理记“处理方应付”，结算单扣款时出现金 | 受理记“退款待付”（欠顾客），处理方执行时冲减应收，在批次净额里体现 | S2、S3 |
| 可用款为负时也能退款 | 可以受理，但执行要等资金覆盖 | S3 |
| 提现失败记冲销 | 记 `PAYOUT_FAILED` 业务分录；超时不释放 | S1、AC-6 |
| 手续费结算 6 条 posting | 5 条 | 复核 |

## 8. v0.2 修订（2026-10-21 开工前审查）

| 修订 | 原因 |
|---|---|
| 请款记账前加准入检查；`source_key` 改用处理方支付编号 | `charge.succeeded` 也可能只是授权；用外部编号让空库重建得到相同的键（AC-4.13） |
| 批次分录省略 0 元 posting，补样例 H；实际手续费大于毛额的行使批次 `HELD` | 失败打款单独重打时，固定 5 条会产生 0 元 posting，违反 INV-1 |
| 批次在 payout 明细就绪后才拉取核对 | `payout.created` 时明细可能还没准备好（Stripe `reconciliation_status`） |
| 退款成功、失败按退款对象的状态认定，不按事件名 | 创建退款的响应可能直接是 `succeeded`，不一定再来一条 `refund.updated` |
| 删去“处理方余额 ≤ 0 不产生批次”；样例 A 第 16～18′ 步改成处理方 pending | Stripe 卡退款只用可用余额，余额为负时会建从银行扣款的负 payout；首版把负 payout 列为不支持并隔离 |
| 金额上限改为 99999999 分；INV-5 加数据库 CHECK 兜底；INV-9 只计 `CAPTURED` 支付 | Stripe 金额最多 8 位；硬约束要落在数据库；撤销的支付不该再计入待结算 |
