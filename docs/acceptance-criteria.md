# LedgerLab 核心约束验收条件

起草：2026-10-20；**v2 重写：2026-10-21（P0 设计）**，按立项复核的资金模型修订（结算在途、批次净额、退款受理与执行分开、提现结果未知、分录封存），新增 AC-7 提现；同日加入交接说明第 14 节的改进：AC-8 Stripe 测试模式、AC-9 公开演示页，模型随机测试提前到 P1。**v2.1：同日开工前审查修订**（Stripe 事实、批次条数、重放定义、跨阶段条目拆分、P1 拆成 P1a～P1c）。尚无代码。

本文件只规定**可观察的结果**（余额、分录、状态、HTTP 状态、错误码、数据库行为），不规定实现。资金模型、分录目录和不变量的正本是 [design/money-model.md](design/money-model.md)；状态机见 [design/state-machines.md](design/state-machines.md)；时间、编号、文件契约见 [design/contracts.md](design/contracts.md)；表结构见 [design/data-dictionary.md](design/data-dictionary.md)；取舍见 [adr/](adr/)。接口路径、错误码、状态名是建议命名，实现时可以调整，但要同步更新本文件和设计文档。

## 0. 通用约定

### 0.1 范围

- 单商户、CAD、整数分（`10000` = CAD 100.00）；单笔支付 50～99999999 分（退款、提现的范围见 contracts 第 3 节）。
- 我们只看到处理方已全额请款的支付（webhook `charge.succeeded` 且通过请款准入检查）；每笔支付一次全额进入某个结算批次。
- 处理方按**批次净额**打款到我们的银行账户：一个批次就是一笔 Stripe payout，净额 = 支付毛额 − 手续费 − 本批扣掉的已执行退款 + 退回的失败打款。处理方的卡退款只用可用余额，不够时 pending；首版只处理净额 > 0 的标准自动 payout，负 payout 等其他 payout 隔离（money-model 第 1 节）。处理方在测试中由说 Stripe 协议子集的模拟器扮演，P6 起也接真实 Stripe 测试模式（ADR-0016）。
- 手续费：合同 `30 + round_half_up(2.9% × 毛额)`，原样转嫁商户；退款不退手续费。
- 退款可多次部分退款；**受理和执行分开**，资金不足时等待。
- 提现：结果不明时 `UNKNOWN`，资金继续占用；只有银行确认失败/取消才释放。
- 三份独立证据：处理方 webhook（驱动记账）、处理方结算单（对账）、银行流水（现金入账和对账）。
- 不做：多商户、多币种、拒付（遇到即隔离，不当成功处理）、部分结算、批次拆分到账的自动应用、平台收入。

### 0.2 账户

| 代码 | 账户 | 类型 | 可为负 |
|---|---|---|---|
| 1101 | 处理方应收 `processor_receivable` | 资产 | 可以（显示为“处理方净应付”，由下一批扣回） |
| 1102 | 结算在途 `settlement_in_transit` | 资产 | 否 |
| 1103 | 银行现金 `bank_cash` | 资产 | **否** |
| 2101 | 商户待结算 `merchant_pending` | 负债 | 否 |
| 2102 | 商户可用 `merchant_available` | 负债 | 可以（显示为“商户欠款”） |
| 2103 | 退款待付 `refund_payable` | 负债 | 否 |
| 2104 | 提现在途 `payout_in_transit` | 负债 | 否 |
| 2105 | 待查收款 `unapplied_cash` | 负债 | 否 |

资产余额 = Σ借 − Σ贷；负债余额 = Σ贷 − Σ借。下文余额只写非零项，其余为 0。

### 0.3 测试层与并发方法

| 代号 | 含义 |
|---|---|
| **TC** | JUnit＋Testcontainers，真实 PostgreSQL；包括以 `app` 角色直连数据库的测试、启动完整应用的 HTTP 测试、和模拟器联跑的测试 |
| **MT** | 基于模型的随机测试（jqwik）：随机操作序列＋故障注入，每一步对照独立的参考模型 |
| **PW** | Playwright，通过页面走关键流程 |

并发场景两种方法都要有：① 起跑栅栏同时放行，**每个场景重复至少 50 次**；② **受控交错**：用测试钩子让第一个事务停在“读完额度、提交之前”，再放第二个事务进来，确定性地制造目标竞争。任何一次违反即失败。

### 0.4 全局不变量（每个 TC、MT 场景结束后检查）

完整定义见 money-model 第 5 节，这里列编号：

- **INV-1** 每个分录 Σ借 = Σ贷，≥ 2 条 posting，金额为正整数分。
- **INV-2** 账户行余额 = 同一快照里从 posting 重算的余额。
- **INV-3** 资产合计 = 负债合计。
- **INV-4** 分录封存：头上的条数、合计等于实际集合；提交后不能追加 posting。
- **INV-5** 1102、1103、2101、2103、2104、2105 不为负。
- **INV-6** 2102 只会因退款受理或纠错变负；提现永远不让它变负，也不超过 `free_cash`。
- **INV-7** 每笔支付 Σ(未失败、未取消的退款) ≤ 请款金额。
- **INV-8** 同一 `source_key` 最多一个分录。
- **INV-9** 业务状态与过渡账户一致（2101/1102/2103/2104 各自等于对应状态对象的金额合计）。
- **INV-10** 每个退款、提现最多预留一次、最多结清或释放一次；结果未知时不释放。
- **INV-11** 银行流水自身平衡；账上银行现金 = 银行期末余额，否则必须有差异记录。

### 0.5 阶段（按依赖，不对应日期；详见 [design/phases.md](design/phases.md)）

每条 AC 只属于一个阶段；阶段完成时，它和之前所有阶段的 AC 都要通过（持续回归）。AC-4.11 的动作集合每个阶段扩展一次，扩展部分算在该阶段。

| 阶段 | 内容 | 覆盖 |
|---|---|---|
| **P1a** 账本与数据库边界 | Maven 骨架、迁移、三角色、资金锁、账户、分录封存、账本级原子性 | AC-1.5–1.9、AC-1.13、AC-8.1 |
| **P1b** 签名入站与恢复 | fake Stripe 的 `charge.succeeded`、webhook 验签、inbox、两个 worker、受控崩溃、短延迟 relay | AC-1.1–1.3、AC-1.4a、AC-1.10、AC-1.12、AC-2.1–2.7、AC-4.3–4.6、AC-8.2 |
| **P1c** 模型测试、重建、页面 | 模型随机测试框架、空库重建一致、最简交易详情页 | AC-1.14、AC-4.11（P1 动作）、AC-4.13 |
| **P2** 结算＋银行入账＋对账 | 手续费、批次＝payout＋balance transactions、明细就绪、结算在途、模拟银行入账、待查收款、两段精确对账、老化 | AC-1.4b、AC-1.11、AC-2.8、AC-2.16、AC-4.1、AC-5.1–5.13、AC-5.15–5.21 |
| **P3** 退款 | 退款状态机、额度、并发、资金门槛与等待队列、处理方可用余额、出站命令、失败与未知结果、API 幂等 | AC-1.4c、AC-2.9–2.15、AC-3、AC-4.2、AC-4.7–4.10、AC-4.12、AC-5.14、AC-8.7 |
| **P4** 纠错 | 误记撤销、冲销＋重记、待查收款清出 | AC-1.4d、AC-6 |
| **P5** 提现 | 双门槛、出站命令、结果未知恢复、失败、退回、提现侧三方对账 | AC-1.4e、AC-7 |
| **P6** 接真实 Stripe 测试模式 | 用户的测试账号、密钥管理、契约测试、真实 webhook 经 chaos relay、对账接真实 balance transactions、运行报告 | AC-8.3–8.6、AC-8.8–8.10 |
| **P7** 部署＋公开演示页 | AWS 部署、“来搞坏它”演示页、性能基线、演示视频、技术文章 | AC-9（部署与性能的验收另写） |
| **P8** 对账调查 Agent | 只读工具、评测 | 另写 |

---

## 1. 标准样例

### 1.1 标准状态

基于 money-model 样例 A 的前几步（P1 毛额 10000，手续费 320）：

| 状态 | 怎么到达 | 余额 | 对象状态 |
|---|---|---|---|
| **S0** | 空账本 | 全 0 | — |
| **S1** | webhook `charge.succeeded` P1 10000 | 应收 10000、待结算 10000 | P1 `CAPTURED` |
| **S2** | S1 ＋ 批次 B1 的 payout 明细就绪（`payout.created`，再 `payout.reconciliation_completed`；P1 10000、费 320、净额 9680） | 在途 9680、可用 9680 | P1 `SETTLED`，B1 `CONFIRMED` |
| **S3** | S2 ＋ 导入银行流水（入账 9680，引用 B1） | 现金 9680、可用 9680 | B1 `BANKED` |

S1→S3 累计 posting：2 ＋ 5 ＋ 2 = 9 条。

### 1.2 全量样例

money-model 第 6 节样例 A～G 的每一步余额都是验收数据，测试逐格对照（AC-1.4）。

---

## AC-1 金额与分录

| ID | 场景 | Given / When / Then | 层 | 阶段 |
|---|---|---|---|---|
| AC-1.1 | 整数分 | **Given** S0 **When** 投递 `charge.succeeded` P1 `amount=10000` **Then** 处理完成后余额 = S1；金额列为 `bigint` | TC | P1b |
| AC-1.2 | 非法金额 | **When** 请款事件金额为 `100.5`、`"100.00"`、`0`、`-500`、`49`、`100000000` 或缺失 **Then** 仍返回 2xx（已入库），事件状态 `REJECTED`、结果 `INVALID_AMOUNT`；没有 payment、分录、posting（inbox 里保留这条拒绝证据）。退款、提现 API 的金额校验见 AC-3.16、AC-7.20 | TC | P1b |
| AC-1.3 | 非 CAD | **When** 请款事件 `currency="usd"` **Then** 事件 `REJECTED`、结果 `UNSUPPORTED_CURRENCY`；没有 payment、分录、posting | TC | P1b |
| AC-1.4a | 手算样例逐格一致（P1） | **Given** money-model 样例 A 第 1 步 **Then** 每个账户余额与表格逐格一致，INV 全部成立 | TC | P1b |
| AC-1.4b | 手算样例（P2） | 样例 A 第 1–3 步（含 2′ 的余额检查）、样例 H，要求同上 | TC | P2 |
| AC-1.4c | 手算样例（P3） | 样例 A 第 1–8 步、样例 D，要求同上 | TC | P3 |
| AC-1.4d | 手算样例（P4） | 样例 E，要求同上 | TC | P4 |
| AC-1.4e | 手算样例（P5） | 样例 A 全部（含 16～18′ 的处理方 pending）及样例 B、C、F、G，要求同上 | TC | P5 |
| AC-1.5 | 数据库拒绝不平衡分录 | **Given** 以 `app` 角色直连 **When** 一个事务写分录：借 应收 10000、贷 待结算 9999，提交 **Then** 提交失败；分录和 posting 都不存在 | TC | P1a |
| AC-1.6 | 分录封存：追加攻击 | **Given** S1（分录 E 已提交）**When** 以 `app` 角色在新事务里向 E 插入一对平衡的 posting（借 应收 1 / 贷 待结算 1）**Then** 插入或提交失败；E 的 posting 仍为 2 条，余额仍为 S1 | TC | P1a |
| AC-1.7 | 分录封存：空分录、单边、条数不符 | **When** 分别提交：只有分录头没有 posting；只有 1 条 posting；头上写 3 条实际 2 条 **Then** 都在提交时失败，无残留 | TC | P1a |
| AC-1.8 | 不可改删、不可清空、账户属性不可改 | **Given** S1 **When** 以 `app` 角色对分录或 posting 执行 `UPDATE`、`DELETE`、`TRUNCATE`，或修改账户的 `kind`/`normal_side`/`allow_negative`/`code` **Then** 都是 SQLSTATE `42501`（没有权限）；数据不变。**When** 以 `migrator` 角色修改账户属性 **Then** 被触发器拒绝（证明触发器本身有效）。**When** 以 `app` 角色把账户余额改成合法但错误的值 **Then** 能执行，但随后的 INV-2 检查必须报错（证明这条检查有效） | TC | P1a |
| AC-1.9 | 不可为负的账户 | **Given** 账本级夹具：用记账接口写入测试分录，使银行现金 9680、商户可用 9680（不依赖 P2 的结算流程）**When** 以记账入口提交一个让银行现金变成 −1 的分录 **Then** 整个事务回滚，余额不变。**When** 以 `app` 角色直连把 1103 的余额改成 −1 **Then** 被 CHECK 拒绝（`23514`） | TC | P1a |
| AC-1.10 | 分录整体成败与唯一性 | **Given** S0 **When** 处理 `charge.succeeded` 时在写完第一条 posting 后注入异常 **Then** 回滚：没有 payment、分录、posting，事件仍待处理；之后重试成功得到 S1。同一 `source_key` 第二次记账被唯一约束拒绝。批次记账的同类要求见 AC-5.16 | TC | P1b |
| AC-1.12 | 请款准入 | **Given** S0 **When** 分别投递 `charge.succeeded`，其中 Charge 为 `captured=false`；`amount_captured` 小于 `amount`；`livemode=true` **Then** 都返回 2xx；事件 `REJECTED`，结果分别为 `UNSUPPORTED_CAPTURE`、`UNSUPPORTED_CAPTURE`、`LIVEMODE`；没有 payment、分录、posting；各开一条差异（`detected_by = INGESTION`） | TC | P1b |
| AC-1.13 | 三个角色 | **When** 以 `readonly` 角色读只读视图 **Then** 成功。**When** 以 `readonly` 读底表、`io` schema、`sim_*` schema，或做任何写入 **Then** 都被拒绝（`42501`）。**When** 以 `app` 角色执行 DDL **Then** 被拒绝。每张 P1 表按 data-dictionary 第 0.1 节的“直连 SQL 验收”各有测试 | TC | P1a |
| AC-1.14 | 最简交易详情页 | **Given** S1，且 `evt_001` 被重复投递一次、`evt_002` 报同一事实 **When** 打开 P1 的交易详情 **Then** 显示金额 `CAD 100.00`、状态 `CAPTURED`、处理方编号 `ch_…`、2 条 posting，以及三次投递和各自结果（`APPLIED`、重复投递、`DUPLICATE_FACT`）；页面数据来自只读查询接口 | PW | P1c |
| AC-1.11 | 页面金额 | **Given** S3 **When** 打开 P1 交易详情 **Then** 显示毛额 `CAD 100.00`、手续费 `CAD 3.20`、净额 `CAD 96.80`，批次 B1 和银行入账，以及 9 条 posting；总览页分别显示在途、银行现金、商户可用（负数时显示“商户欠款”） | PW | P2 |

## AC-2 幂等与去重

四层，各管一件事（ADR-0009）：webhook `event_id`（inbox，永久）、API `Idempotency-Key`（24 小时）、业务操作编号（`merchant_refund_ref`、`merchant_payout_ref`，永久）、分录 `source_key`（永久）。

webhook 入口：`POST /webhooks/stripe`，签名用 Stripe 的方案（`Stripe-Signature: t=<signed_at>,v1=<hmac>`，HMAC-SHA256，密钥 `whsec_…`）；模拟器用自己的密钥按同一方案签名。处理是异步的：入口只验签、入库、返回 2xx；结果看 inbox 状态。

| ID | 场景 | Given / When / Then | 层 | 阶段 |
|---|---|---|---|---|
| AC-2.1 | 验签失败 | **When** 签名错误、缺签名头、或请求体被改了一个字节 **Then** 401；inbox 无新行 | TC | P1b |
| AC-2.2 | 签名时间与业务时间分开 | **When** 签名时间与服务器时间相差 300 秒（过去或未来）**Then** 2xx；相差 301 秒（过去或未来）**Then** 401。**When** 一个 3 天前发生的旧事件用新签名重新投递 **Then** 2xx，按 `event_id` 去重；签名时间不写进任何业务时间字段 | TC | P1b |
| AC-2.3 | 重复投递同内容 | **Given** `evt_001`（P1 请款）已处理，S1 **When** 同 ID 同内容再投递 **Then** 2xx；inbox 行数不变；posting 数不变 | TC | P1b |
| AC-2.4 | 同事件 ID 不同内容 | **Given** 同上 **When** `evt_001` 再来，金额改成 9000 **Then** 2xx；冲突内容另存一行到 `io.inbox_conflict`，开差异 `EVENT_CONTENT_CONFLICT`；原事件行（状态、结果、内容）和余额都不变 | TC | P1b |
| AC-2.5 | 不同事件 ID 报同一事实 | **Given** S1 **When** `evt_002` 也报 P1 请款 10000 **Then** `evt_002` 状态 `PROCESSED`、结果 `DUPLICATE_FACT`；posting 数不变 | TC | P1b |
| AC-2.6 | 同一支付不同金额 | **Given** S1 **When** `evt_003` 报 P1 请款 9000 **Then** `evt_003` 状态 `REJECTED`、结果 `BUSINESS_CONFLICT`，开差异 `EVENT_BUSINESS_CONFLICT`（`detected_by = INGESTION`）；余额仍为 S1 | TC | P1b |
| AC-2.7 | 并发重复处理 | **Given** S0 **When** `evt_001` 和 `evt_002`（同一事实）被两个 worker 同时处理 **Then** 恰好一个分录；余额 = S1 | TC | P1b |
| AC-2.8 | 批次重复 | **Given** S2 **When** B1 的确认事件以新 `event_id` 再到，内容相同；以及两个这样的事件并发处理 **Then** 都不再记账；批次分录恰好 1 个、posting 5 条 | TC | P2 |
| AC-2.9 | 多种证据报同一退款执行 | **Given** 退款 R1 已提交 **When** 调用响应（`status=succeeded`）、`refund.created`、`refund.updated`（都带 `status=succeeded`）和含 R1 行的批次先后（及并发）到达 **Then** `REFUND_EXECUTED` 恰好一个；批次正常记账 | TC | P3 |
| AC-2.10 | API 同键同内容 | **Given** 用 `Idempotency-Key: K1` 受理退款 R1 2000，返回 201 **When** K1 同内容重发 **Then** 返回同样的状态码和响应体（同一 `refund_id`）；无新行 | TC | P3 |
| AC-2.11 | API 同键不同内容 | **When** K1 改金额重发 **Then** 422 `IDEMPOTENCY_KEY_REUSED`；无写入 | TC | P3 |
| AC-2.12 | API 同键并发首次 | **When** 两个请求同时用 K2 同内容发起 **Then** 只有一笔退款；另一个返回同一结果或 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS` | TC | P3 |
| AC-2.13 | 缺幂等键 | **When** 退款或提现请求不带 `Idempotency-Key` **Then** 400 `IDEMPOTENCY_KEY_REQUIRED` | TC | P3 |
| AC-2.14 | 幂等记录过期后重放 | **Given** R1 用 K1、`merchant_refund_ref=MR-1` 受理，当时结果是 `AWAITING_FUNDS` **When** 时钟前进 25 小时、幂等记录被清理，再用新键 K9、同一 `MR-1`、同金额提交 **Then** 返回**原受理结果**（同一 `refund_id`、状态 `AWAITING_FUNDS`），不是新退款；退款行数、posting 数不变 | TC | P3 |
| AC-2.15 | 业务编号不同内容 | **When** 用 `MR-1` 但金额不同提交 **Then** 409 `BUSINESS_REF_CONFLICT`；无写入 | TC | P3 |
| AC-2.16 | 页面演示重复回调 | **Given** S3 **When** 演示脚本重放 B1 确认事件，刷新交易详情 **Then** 事件日志多一条“重复”，分录和余额不变 | PW | P2 |

## AC-3 退款

受理：锁支付行，检查剩余可退额（INV-7），记 `REFUND_ACCEPTED`（借 可用 / 贷 退款待付）。执行门槛：等待队列按受理顺序放行最长前缀，使 `Σ前缀 ≤ 可用 + Σ等待中`（money-model 4.1）。放行即建出站命令。退款永远不直接动银行现金。

| ID | 场景 | Given / When / Then | 层 | 阶段 |
|---|---|---|---|---|
| AC-3.1 | 正常部分退款全流程 | **Given** S3 ＋ P2 请款 5000（样例 A 第 4 步），fake Stripe 里 P2 的资金已可用 **When** 退款 R1 2000 **Then** 201，状态 `READY`，有一条 `PENDING` 出站命令；可用 7680、退款待付 2000、现金 9680、应收 5000。**When** 命令发出，处理方响应 `status=succeeded` **Then** `SUCCEEDED`；退款待付 0、应收 3000（样例 A 第 6 步）。**When** 批次 B2（P2 5000、费 175、R1 −2000，净额 2825）到达 **Then** 记账通过；在途 2825；可用 12505 | TC | P3 |
| AC-3.2 | 并发 6000 + 6000 | **Given** S3 **When** 两个不同键的请求同时各退 6000 **Then** 恰好一个 201、一个 409 `REFUND_EXCEEDS_REMAINING`；退款行 1 条；可用 3680、退款待付 6000、现金 9680；新增 posting 恰好 2 条；处理方模拟器只收到一笔退款请求 | TC | P3 |
| AC-3.3 | 并发 5000 + 5000，资金不足等待 | **Given** S3 **When** 两笔 5000 同时发起 **Then** 两个都 201；可用 −320（页面显示“商户欠款 CAD 3.20”）、退款待付 10000、现金 9680；先受理的 `READY`，后受理的 `AWAITING_FUNDS` 且没有出站命令。**When** 批次 B2（P2 5000、费 175，净额 4825；两笔退款都还没执行）记账 **Then** 可用 4505，第二笔转 `READY` 并建命令 | TC | P3 |
| AC-3.4 | 已有退款后再并发 | **Given** S3 ＋ R1 2000 已受理、未执行 **When** 两笔 5000 同时发起 **Then** 一个 201、一个 409；可用 2680、退款待付 7000 | TC | P3 |
| AC-3.5 | 超额单笔 | **Given** 同 AC-3.4 **When** 退款 9000 **Then** 409 `REFUND_EXCEEDS_REMAINING`；无写入 | TC | P3 |
| AC-3.6 | 未结算支付的退款 | **Given** S1 **When** 退款 2000 **Then** 201，`AWAITING_FUNDS`；可用 −2000、退款待付 2000。**When** B1 记账 **Then** 可用 7680，退款转 `READY` | TC | P3 |
| AC-3.7 | 等待队列不插队 | **Given** 可用 1500 **When** 依次受理 Ra 2000、Rb 1000 **Then** 两者都 `AWAITING_FUNDS`，可用 −1500。**When** 可用增加 1000（变 −500）**Then** Ra 放行、Rb 继续等待（即使 Rb 单独看够钱） | TC | P3 |
| AC-3.8 | 退款失败 | **Given** 可用 5000 **When** 受理 R3 1000 并提交，处理方返回失败 **Then** `FAILED`；记 `REFUND_FAILED`；可用 5000、退款待付 0；原支付的剩余可退额恢复；不是 `REVERSAL` | TC | P3 |
| AC-3.9 | 取消等待中的退款 | **Given** 一笔 `AWAITING_FUNDS` 的退款 **When** 运营带原因取消 **Then** `CANCELLED`，记 `REFUND_CANCELLED`，额度恢复。**When** 取消一笔 `READY` 或之后状态的退款 **Then** 409 `REFUND_NOT_CANCELLABLE` | TC | P3 |
| AC-3.10 | 提交超时进入未知 | **Given** R1 `READY` **When** 处理方模拟器对提交请求超时 10 次，时钟前进 7 天 **Then** R1 一直是 `UNKNOWN`，从不自动变 `FAILED`；退款待付仍为 2000；所有重试用同一请求号，处理方最多执行一次；开老化差异 `REFUND_NOT_EXECUTED` | TC | P3 |
| AC-3.11 | 提交后崩溃 | **Given** R1 命令 `IN_FLIGHT` **When** 处理方已受理、我们在写回回执前进程被杀 **Then** 重启后租约过期被接管；接管者先查询，状态变 `SUBMITTED`；处理方只有一笔退款 | TC | P3 |
| AC-3.12 | 受理事务中途失败 | **Given** S3 **When** 受理事务在写完退款行、写 posting 前注入异常 **Then** 没有退款行、posting、出站命令，幂等记录不处于已完成；余额仍为 S3 | TC | P3 |
| AC-3.13 | 锁是必要的 | **Given** 去掉支付行锁的故障版本 **When** 用受控交错跑 AC-3.2 **Then** 测试必须失败（出现超退）；恢复锁后通过。用来证明测试真的能抓到这个错误 | TC | P3 |
| AC-3.14 | 银行现金不被退款触碰 | **Then** AC-3.1～3.12 中所有退款相关分录都不含 1103 的 posting | TC | P3 |
| AC-3.16 | 退款金额校验 | **When** 退款金额为 `100.5`、`"20.00"`、`0`、`-1` 或缺失 **Then** 400 `INVALID_AMOUNT`；无写入。**When** 100 分的支付已退 51 分，再退 49 分 **Then** 201（退款没有 50 分下限） | TC | P3 |
| AC-3.17 | 创建即成功、证据先后 | **Given** S3 ＋ P2 资金可用 **When** fake Stripe 对 `POST /v1/refunds` 直接返回 `status=succeeded`，之后不再发任何退款事件 **Then** 退款 `SUCCEEDED`，`REFUND_EXECUTED` 恰好一个。**When** 另一笔退款的 `refund.created`（`status=succeeded`）比调用响应先到 **Then** 结果相同，调用响应到达后不再改变 | TC | P3 |
| AC-3.18 | 处理方可用余额不足 | **Given** 样例 A 第 15 步 **When** 放行 R2 3000，fake Stripe 可用余额为 0 **Then** 处理方返回 `status=pending`，R2 `SUBMITTED`，退款待付 3000、应收 0。**When** P4 请款 5000 且资金在处理方变为可用 **Then** 处理方执行 R2，`SUCCEEDED`，应收 2000（样例 A 第 18′ 步） | TC | P3 |
| AC-3.15 | 页面演示 | **Given** S3 **When** 演示脚本并发两笔 6000 **Then** 页面一笔成功、一笔被拒（含原因），可用 CAD 36.80。**When** 并发两笔 5000 **Then** 页面显示“商户欠款 CAD 3.20”和一笔等待资金的退款 | PW | P3 |

## AC-4 乱序、重放与故障恢复

| ID | 场景 | Given / When / Then | 层 | 阶段 |
|---|---|---|---|---|
| AC-4.1 | 批次早于支付 | **Given** S0 **When** B1 确认事件先到 **Then** B1 `HELD(UNKNOWN_PAYMENT)`，无 posting。**When** P1 请款到达 **Then** 同一事务里 P1 记账、B1 重新核对并记账；余额 = S2。支付一直不到时 B1 保持 `HELD`，运营页可见 | TC | P2 |
| AC-4.2 | 退款执行早于回执 | **Given** R1 `READY`，命令还没写回回执 **When** `refund.updated`（status=succeeded） 先到 **Then** `READY → SUCCEEDED`，记账一次；之后回执到达不再改变状态 | TC | P3 |
| AC-4.3 | 整段重放 | **Given** 一组已处理的事件，至少包含正常请款、同 ID 重投、不同 ID 报同一事实、被拒绝的事件 **When** 全部按原 ID、新签名再投递 **Then** 全部判为重复；inbox 行数、posting 数和余额不变 | TC | P1b |
| AC-4.4 | 入库后、返回 2xx 前崩溃 | **When** 事件已写 inbox，进程在响应前被杀，处理方重试 **Then** 只有一行 inbox，只处理一次 | TC | P1b |
| AC-4.5 | 入库后、处理前崩溃 | **When** 事件已入库、未处理时进程被杀 **Then** 重启后被处理，恰好一次 | TC | P1b |
| AC-4.6 | 处理中 worker 崩溃 | **When** worker 持有租约时被杀（杀的是 worker 进程，数据库保持运行，用测试钩子确认已停在注入点）**Then** 租约过期后另一个 worker 接管；业务结果和分录恰好一次。**When** 旧 worker 只是卡住、在新 worker 提交后才恢复并尝试写回 **Then** 写回因 `lease_epoch` 不符失败，整个事务回滚，不覆盖新结果、不多记账 | TC | P1b |
| AC-4.7 | API 已提交但响应丢失 | **Given** S3 **When** 退款 2000（K3）提交成功后、响应发出前断网，客户端用 K3 重试 **Then** 返回原结果（同一 `refund_id`）；退款行 1 条；可用 7680 | TC | P3 |
| AC-4.8 | API 提交前崩溃 | **Given** S3 **When** 退款 2000（K4）提交前注入异常，再用 K4 重试 **Then** 第一次无任何写入；重试成功 | TC | P3 |
| AC-4.9 | 晚到的成功与超时竞争 | **When** 命令判 `UNKNOWN` 的事务与 `refund.updated`（status=succeeded） 的处理并发 **Then** 最终 `SUCCEEDED`，`REFUND_EXECUTED` 恰好一个 | TC | P3 |
| AC-4.10 | 挂起事件老化 | **When** 一个依赖对象一直不来的事件停在 `PARKED` 超过 1 小时 **Then** 运营页面和差异列表可见 `EVENT_PARKED_AGED` | TC | P3 |
| AC-4.11 | 模型随机测试 | **Given** 固定种子 **When** 生成至少 1000 条操作序列（P1：请款、重复投递、乱序、任意步骤崩溃；P2 加批次、明细就绪、银行入账、payout 失败与重打；P3 加退款、退款失败、处理方超时、处理方可用余额不足；P4 加纠错；P5 加提现、银行超时、丢回执、退回；每个动作写明前置条件，序列长度、崩溃点集合写在报告里）在真实 PostgreSQL 上执行 **Then** 每一步 INV-1～INV-11 成立，余额与参考模型一致；失败时输出可重放的最短序列。参考模型不复用业务代码。报告里如实写：它只覆盖生成器能表达的情况，不等于并发测试。CI 每次 ≥ 1000 条，夜间任务更多，报告条数和违反次数 | MT | P1c 建立，每阶段扩展 |
| AC-4.12 | 页面演示故障重试 | **When** 演示脚本注入 AC-4.7 的失败并重试 **Then** 页面分录和余额正确；日志能看出“第一次失败、第二次返回原结果” | PW | P3 |
| AC-4.13 | 空库重建一致 | **Given** AC-4.11 的任意一条序列跑完，并保存了完整的场景输入日志（入站原文、出站调用的回执、API 命令、导入的文件、拉取的快照、时钟推进）**When** 在空库上单线程按顺序重放这份日志，编号用同一种子的 `IdGenerator`，并处理到 inbox 和出站命令都没有待办 **Then** 最终余额、按外部身份对齐的对象状态、按 `source_key` 归并的分录集合（类型、账户、方向、金额）与原库完全相同（ADR-0018 补充） | MT | P1c |

## AC-5 结算、银行入账与对账

### 5.1 夹具独立性

- 结算单和银行流水由模拟器按**场景文件**生成，处理方和银行各有自己的状态；生成器不读账本、不调主应用。
- 预期差异写在场景文件里（ground truth），不由账本推导。架构测试检查依赖方向。
- 银行流水来自模拟银行的执行记录，不复制处理方的“打算汇款”。

### 5.2 差异类别（首版）

| 类别 | 定义 |
|---|---|
| `MISSING_IN_LEDGER` | 结算单有行，本地没有对应的支付/退款 |
| `MISSING_IN_STATEMENT` | 本地已结算的支付（或已执行的退款）在对应结算单里没有行 |
| `AMOUNT_MISMATCH` | 两边身份对上，毛额或净额不同（零容差） |
| `FEE_MISMATCH` | 实际手续费 ≠ 合同预期手续费 |
| `DUPLICATE_STATEMENT_LINE` | 同一 `statement_line_id` 出现不止一次 |
| `BATCH_TOTAL_MISMATCH` | 本地批次净额 ≠ 结算单批次净额（按行身份去重之后比较） |
| `UNSUPPORTED_BATCH_LINE` | 批次含不支持的行（如 adjustment、dispute），`HELD(UNSUPPORTED_LINE)`；actual 列出各行 balance transaction 编号、type 和金额，不记账 |
| `UNSUPPORTED_EVENT` | 不支持的入站事件或事件准入失败；P3 T02 前已开的批次主体旧差异保留原类别 |
| `TIMING_DIFFERENCE` | 跨截止时点、尚未到期的未完成项；有到期时间，到期未解释则升级 |
| `REFUND_NOT_IN_STATEMENT` | 已执行退款超过阈值仍没出现在结算单 |
| `DEPOSIT_MISSING` / `DEPOSIT_AMOUNT_MISMATCH` | 已确认批次没有银行入账 / 金额不等 |
| `UNMATCHED_BANK_LINE` | 银行流水行精确匹配不到批次或提现 |
| `UNAPPLIED_CASH_OPEN` | 待查收款有未清项 |
| `BANK_BALANCE_MISMATCH` | 账上银行现金 ≠ 银行期末余额 |
| `BATCH_VERSION_CONFLICT` / `STATEMENT_VERSION_CONFLICT` | 同一批次号或行身份、内容不同 |
| 老化类 | `PAYMENT_NOT_SETTLED`、`DEPOSIT_NOT_RECEIVED`、`REFUND_AWAITING_FUNDS_AGED`、`REFUND_NOT_EXECUTED`、`PAYOUT_UNRESOLVED`、`EVENT_PARKED_AGED`（阈值见 contracts 1.3） |

类别不强制互斥。

### 5.3 标准对账样例

手续费：10000→320、5000→175（合同）、7000→233、3000→117、6000→204、2500→103、4000→146。时间均为 America/Toronto。

- **本地（来自 webhook）**：P1 10000、P2 5000、P3 7000、P6 4000 于 2026-11-10（周二）白天请款；P4 3000 于 2026-11-10 23:50 请款；P5 6000 于 2026-11-05（周四）请款。
- **批次 webhook B1**（结算日 11-10）：P1 费 320、P2 费 **205**、P3 费 233、P6 费 146；净额 9680 + 4795 + 6767 + 3854 = **25096**。本地照此记账。
- **处理方结算数据 11-10**（快照：`created` 在 11-10 当天的 payout，以及每个 payout 的全部 balance transactions；11-11 03:00 拉取）：B1 行：L1 P1 10000/320；L2 P2 5000/205；L3 P3 7000/233；**L3 原样再出现一次**；L4 **P9** 2500/103；快照完整（分页拉完）；处理方批次净额 9680 + 4795 + 6767 + 2397 = **23639**。
- **银行流水 11-12**（11-11 是非工作日）：入账 25096，引用 B1，流水 T1，`booking_date` 11-12，11-12 07:00 导入；期初 0、期末 25096。
- 对账运行：业务截止 `cutoff_at` = 2026-11-11 00:00；证据快照时点 `evidence_as_of` = 2026-11-12 08:00（contracts 1.2 第 3 条，2026-10-23 修订）。B1 在截止前确认，T1 虽然记账日在截止之后，但在证据时点之前已导入，所以能解释 B1，银行一段无差异；账上银行现金 25096 = 期末 25096。
- 工作日按 contracts 1.2 第 7 条的日历（11-11 非工作日）：P5 从 11-05 起已过 11-06、11-09、11-10 三个工作日，超过 2 个工作日的阈值；P4 的到期时刻是 11-14 00:00（11-12、11-13 两个工作日之后）。

| ID | 场景 | Given / When / Then | 层 | 阶段 |
|---|---|---|---|---|
| AC-5.1 | 批次净额记账 | **Given** S1 **When** B1（P1 10000、费 320）确认 **Then** 一个 `BATCH_SETTLED` 分录、5 条 posting；余额 = S2 | TC | P2 |
| AC-5.2 | 批次逐项核对零容差 | **Given** 本地 P1 10000 **When** B1 的 P1 行毛额是 9999 **Then** B1 `HELD`，开 `AMOUNT_MISMATCH`，无批次分录 | TC | P2 |
| AC-5.3 | 批次版本冲突 | **Given** S2 **When** B1 以不同内容再到 **Then** 原批次和分录不变；新内容存为冲突版本；开 `BATCH_VERSION_CONFLICT` | TC | P2 |
| AC-5.4 | 银行入账才转现金 | **Given** S2 **Then** 银行现金 0、在途 9680。**When** 导入银行流水，入账 9680、引用 B1 **Then** 余额 = S3，B1 `BANKED` | TC | P2 |
| AC-5.5 | 入账金额不符 | **Given** S2 **When** 银行入账 9679、引用 B1 **Then** 记 `UNAPPLIED_CASH_RECEIVED`：现金 9679、待查收款 9679；B1 仍 `CONFIRMED`；开 `DEPOSIT_AMOUNT_MISMATCH`，候选 B1 | TC | P2 |
| AC-5.6 | 重复打款与重复导入 | **Given** S3 **When** 银行流水另有一行入账 9680、引用 B1、流水编号 T2 **Then** 记待查收款 9680，开 `UNMATCHED_BANK_LINE`（money-model 样例 F）。**When** 同一份流水文件再导入一次 **Then** 不新增任何行和分录 | TC | P2 |
| AC-5.7 | 标准样例识别全部差异 | **Given** 5.3 样例 **When** 运行对账 **Then** 恰好得到 7 条：P2 `FEE_MISMATCH`（预期 175、实际 205）；L3 `DUPLICATE_STATEMENT_LINE`；P9 `MISSING_IN_LEDGER`；P6 `MISSING_IN_STATEMENT`；B1 `BATCH_TOTAL_MISMATCH`（25096 对 23639，差 1457）；P4 `TIMING_DIFFERENCE`（未完成，有到期时间）；P5 `PAYMENT_NOT_SETTLED`（已过 3 个工作日）。P1、P3 无差异；银行一段无差异。误报 0 | TC | P2 |
| AC-5.8 | 对账不改账 | **When** 运行对账（包括 dry run）**Then** posting 和余额与运行前逐行相同；正式运行只写 `recon` 表，dry run 什么都不写 | TC | P2 |
| AC-5.9 | 可重放、不重复报警 | **Given** AC-5.7 已运行 **When** 同样输入、规则版本、截止时点再跑 **Then** 结果完全相同，差异不重复生成。**When** 再拉一份结算数据（11-12 创建的 payout B2，含 P4 一行），以 `cutoff_at` 11-13 00:00、`evidence_as_of` 11-13 08:00 再跑 **Then** P4 的 `TIMING_DIFFERENCE` 转 `EXPLAINED`，并记下解释它的证据（B2 的行）；第一次运行的发现原样保留（state-machines 第 7 节） | TC | P2 |
| AC-5.10 | 文件完整性与版本 | **When** 导入文件尾行数或合计不符的银行流水，或分页没拉完的处理方快照 **Then** 整个输入拒绝（`FILE_INCOMPLETE`），保留原始数据。**When** 同一天的新版本里同一行身份内容不同 **Then** 旧行不被覆盖，开 `STATEMENT_VERSION_CONFLICT` | TC | P2 |
| AC-5.11 | 老化逐笔检查 | **Given** 待结算账户总额看上去合理 **When** 其中一笔支付超过阈值没有进入批次 **Then** 这一笔被单独报出；报告注明老化只能发现“有开始记录、没有结束记录”的义务 | TC | P2 |
| AC-5.12 | 银行余额核对 | **When** 银行流水 期初 + 入账 − 出账 ≠ 期末 **Then** 文件拒绝。**When** 流水自身平衡，但账上银行现金 ≠ 期末余额 **Then** 开 `BANK_BALANCE_MISMATCH`，显示差额 | TC | P2 |
| AC-5.13 | 夹具不来自账本 | **Then** 预期差异只来自场景文件；模拟器不依赖主应用模块（架构测试通过）；主应用看不到模拟器的库 | TC | P2 |
| AC-5.14 | 退款行 | **Given** 同一支付两笔部分退款 R1 2000、R2 1000 都已执行 **When** 结算单含两行退款 **Then** 两行分别按 `processor_refund_ref` 匹配，不算重复。**When** 有一笔已执行退款超过阈值仍不在结算单 **Then** `REFUND_NOT_IN_STATEMENT` | TC | P3 |
| AC-5.16 | 批次记账整体成败 | **Given** S1 **When** 批次记账在写完第 3 条 posting 后注入异常 **Then** 回滚：余额仍为 S1，批次仍为 `RECEIVED`；重试成功得到 S2 | TC | P2 |
| AC-5.17 | payout 明细就绪 | **When** `payout.created` 到达时 fake Stripe 的 `reconciliation_status=in_progress` **Then** 批次 `ANNOUNCED`，不拉明细、不记账。**When** 第一次拉取只拿到部分明细（模拟分页中断）**Then** 不形成批次版本，不开 `BATCH_VERSION_CONFLICT`。**When** `payout.reconciliation_completed` 到达（包括比 `payout.created` 先到）**Then** 拉完全部明细，核对后 `CONFIRMED`。**When** 一笔手动 payout 或负 payout 到达 **Then** 批次 `HELD(UNSUPPORTED_PAYOUT)`，开差异，不记账 | TC | P2 |
| AC-5.18 | 失败打款重新打出 | **Given** 样例 H 第 H1 步 **When** 只含一行 `PAYOUT_FAILURE` +9680 的批次到达 **Then** `BATCH_SETTLED` 恰好 2 条 posting（借 在途 9680 / 贷 应收 9680），没有 0 元 posting；可用不变 | TC | P2 |
| AC-5.19 | 实际手续费大于毛额 | **When** 批次里某行毛额 50、实际手续费 60 **Then** 批次 `HELD(FEE_EXCEEDS_GROSS)`，无批次分录，开差异 | TC | P2 |
| AC-5.20 | 银行证据早到 | **Given** S1，B1 只到 `ANNOUNCED` **When** 导入银行流水，入账 9680、引用 B1 **Then** 流水行存为 `WAITING`，不记账，现金仍 0；银行余额核对把差额 9680 报为 `TIMING_DIFFERENCE`（不报 `BANK_BALANCE_MISMATCH`）。**When** B1 明细就绪并确认 **Then** 同一事务里记 `BATCH_SETTLED` 和 `BANK_DEPOSIT_RECEIVED`，余额 = S3，B1 `BANKED`。**When** 另一笔引用 `po_` 未知批次的入账等满 2 个工作日 **Then** 记 `UNAPPLIED_CASH_RECEIVED`，开 `UNMATCHED_BANK_LINE`。**When** 入账行币种不是 CAD、方向是出账或状态不是 `BOOKED` **Then** 都不和 B1 匹配（money-model 第 3 节的匹配条件） | TC | P2 |
| AC-5.21 | 差异再次出现 | **Given** AC-5.7 已运行 **Then** 每条差异都有一条该运行的发现记录；发现只追加、不可改（`app` 改删被拒）。**When** 运营把 P2 的 `FEE_MISMATCH` 标为 `ACCEPTED`（写原因）后同样输入再跑 **Then** 差异保持 `ACCEPTED`，新运行仍有这条发现。**When** 之后一次运行里 P2 的实际手续费变了（内容指纹不同）**Then** 差异重开为 `OPEN`，状态历史多一行。**When** 已 `EXPLAINED` 的差异再次被发现 **Then** 重开为 `OPEN`。INGESTION 差异不参与自动解释 | TC | P2 |
| AC-5.15 | 对账工作台 | **Given** AC-5.7 的结果 **When** 点击 B1 的差异 **Then** 显示两边金额和差额，能跳到批次行、原事件、相关 posting 和银行流水行 | PW | P2 |

## AC-6 纠错有边界

决定：**没有通用改账接口**（ADR-0011）。分录只来自业务事件或受限纠错。退款、提现失败、提现退回是业务事件，不是冲销。受限纠错 = 一条纠错记录 ＋ 对原分录的完整 `REVERSAL` ＋ 零或一个 `CORRECTION`，同一事务，必须关联原因和证据。

| ID | 场景 | Given / When / Then | 层 | 阶段 |
|---|---|---|---|---|
| AC-6.1 | 没有通用改账接口 | **When** 对分录、posting、账户余额发送 `POST`/`PUT`/`PATCH`/`DELETE` **Then** 404 或 405 | TC | P4 |
| AC-6.2 | 业务事件不是冲销 | **Then** 退款受理、退款执行、退款失败、退款取消的分录类型分别是各自的 `entry_type`，`reverses_entry_id` 为空（提现失败和退回的同类要求见 AC-7.9、AC-7.10） | TC | P4 |
| AC-6.3 | 撤销误记的请款 | **Given** S1（P1 被误记，未结算）**When** 带原因和证据提交纠错，冲销 P1 的请款分录 E **Then** 新分录 R：`REVERSAL`、`reverses_entry_id=E`，借 待结算 10000 / 贷 应收 10000；E 原样保留；余额全 0；P1 `VOIDED` | TC | P4 |
| AC-6.4 | 已结算的钱不能靠撤销拿回 | **Given** S2 **When** 撤销 P1 **Then** 409 `USE_REFUND_FOR_SETTLED_PAYMENT`；无写入 | TC | P4 |
| AC-6.5 | 冲销加重记 | **Given** money-model 样例 E（P5 记成 4500，B6 `HELD`）**When** 提交纠错：冲销 4500 的请款分录，重记 5400，证据为差异编号和结算单行 **Then** 生成 `REVERSAL` 和 `CORRECTION` 各一个；B6 重新核对后记账；页面能从差异点到纠错和证据 | TC | P4 |
| AC-6.6 | 不能重复冲销 | **When** 再次冲销 E，或冲销 R **Then** 409 `ALREADY_REVERSED` / `CANNOT_REVERSE_REVERSAL` | TC | P4 |
| AC-6.7 | 原因和证据必填 | **When** 缺原因或证据 **Then** 400；无写入 | TC | P4 |
| AC-6.8 | 纠错原子 | **When** 写完 `REVERSAL` 后、写 `CORRECTION` 前注入异常 **Then** 三者都不存在 | TC | P4 |
| AC-6.9 | 待查收款清出 | **Given** AC-5.6 的待查收款 9680 **When** 银行流水出现出账行 9680，引用 T2（退还处理方）**Then** 记 `UNAPPLIED_CASH_RETURNED`：待查收款 0、现金 9680；差异 `RESOLVED` | TC | P4 |

## AC-7 提现

提现门槛（money-model 4.2）：`P ≤ 可用` 且 `P ≤ free_cash = 现金 − 提现在途 − 待查收款 − max(0, −应收)`。受理记 `PAYOUT_RESERVED`（借 可用 / 贷 提现在途）并建出站命令。银行请求号 = 提现编号，所有重试相同。现金只在银行流水出账时减少。

| ID | 场景 | Given / When / Then | 层 | 阶段 |
|---|---|---|---|---|
| AC-7.1 | 正常提现 | **Given** S3 **When** 提现 9680 **Then** 201 `RESERVED`；可用 0、提现在途 9680、现金 9680。**When** 银行回执已受理 **Then** `ACCEPTED`，无分录。**When** 银行流水出账 9680 **Then** `PAID`；全部为 0 | TC | P5 |
| AC-7.2 | 超过商户余额 | **Given** S3 **When** 提现 9681 **Then** 409 `INSUFFICIENT_FUNDS`，原因 `MERCHANT_BALANCE`；无写入 | TC | P5 |
| AC-7.3 | 钱还在途 | **Given** S2 **When** 提现 100 **Then** 409，原因 `FREE_CASH`；无写入 | TC | P5 |
| AC-7.4 | 欠处理方的钱占用现金 | **Given** 账本级夹具：可用 3854、现金 3854、应收 −3000（fake Stripe 的“处理方余额为负”场景；真实 Stripe 正常流程下不会出现）**When** 提现 855 **Then** 409，原因 `FREE_CASH`；**When** 提现 854 **Then** 201 | TC | P5 |
| AC-7.5 | 待查收款不能被提走 | **Given** AC-5.6 之后（现金 19360、可用 9680、待查收款 9680）**When** 提现 9680 **Then** 201；**When** 再提 1 **Then** 409 | TC | P5 |
| AC-7.6 | 超时进入未知，不释放 | **Given** 提现 PO1 9680 已受理 **When** 调银行超时 **Then** `UNKNOWN`；可用仍为 0；再申请提现 100 被拒（`MERCHANT_BALANCE`） | TC | P5 |
| AC-7.7 | 未知永不自动失败 | **Given** PO1 `UNKNOWN` **When** 银行连续 10 次不可达，时钟前进 5 个工作日 **Then** 仍 `UNKNOWN`，提现在途 9680；开 `PAYOUT_UNRESOLVED` | TC | P5 |
| AC-7.8 | 银行已付款但回执丢失 | **Given** 模拟银行执行了 PO1、但丢掉回执 **When** 我们用同一请求号重试 **Then** 银行返回已存在的付款，模拟银行里只有一笔付款；流水出账后 `PAID`；现金只减少一次 | TC | P5 |
| AC-7.9 | 确认失败才释放 | **Given** PO1 `UNKNOWN` **When** 查询得到银行明确拒绝，且流水里没有出账 **Then** `FAILED`，记 `PAYOUT_FAILED`（`reverses_entry_id` 为空）；可用 9680、提现在途 0；等待资金的退款重新计算 | TC | P5 |
| AC-7.10 | 出账后被退回 | **Given** PO1 `PAID` **When** 银行流水出现退回 9680，引用 PO1，原因 `ACCOUNT_CLOSED` **Then** `RETURNED`，记 `PAYOUT_RETURNED`（`reverses_entry_id` 为空）；现金 9680、可用 9680 | TC | P5 |
| AC-7.11 | 冲突证据 | **Given** PO2 已 `FAILED` **When** 流水出现 PO2 的出账 **Then** 不自动记账；开 `PAYOUT_STATE_CONFLICT`；`BANK_BALANCE_MISMATCH` 显示差额 | TC | P5 |
| AC-7.12 | 认不出的出账 | **When** 流水出现引用不认识的出账 **Then** 不记账；开 `UNMATCHED_BANK_LINE` 和 `BANK_BALANCE_MISMATCH` | TC | P5 |
| AC-7.13 | 重复的银行证据 | **When** 同一个 `bank_txn_id` 的出账在两个文件版本里出现，或银行回执重复到达 **Then** `PAYOUT_PAID` 恰好一个 | TC | P5 |
| AC-7.14 | 退款与提现并发 | **Given** 可用 1000、现金 1000 **When** 同时退款 800、提现 1000（栅栏 50 次＋两种受控交错）**Then** 只有两种结局：退款先 → 提现 409；提现先 → 退款 `AWAITING_FUNDS`、可用 −800。绝不出现提现成功且退款立即执行 | TC | P5 |
| AC-7.15 | 并发提现 | **Given** S3 **When** 两笔 6000 同时提现 **Then** 一个 201、一个 409；提现在途 6000 | TC | P5 |
| AC-7.16 | 命令崩溃点 | **When** 分别在“命令已落库、未发出”“已发出、回执未写回”两个点杀进程 **Then** 重启后都能恢复；模拟银行里每个提现最多一笔付款 | TC | P5 |
| AC-7.17 | 业务编号永久 | **Given** `merchant_payout_ref=MP-1` 已受理 **When** 幂等记录过期后用新键、同一 `MP-1` 再提交 **Then** 返回原结果，不新建提现 | TC | P5 |
| AC-7.18 | 提现侧三方对账 | **When** 运行银行段对账 **Then** 每笔 `PAID`/`RETURNED` 提现精确匹配到流水行；`ACCEPTED` 超过阈值没有出账的报 `PAYOUT_UNRESOLVED` | TC | P5 |
| AC-7.20 | 提现金额校验 | **When** 提现金额为 `100.5`、`"20.00"`、`0`、`-1`、`100000000` 或缺失 **Then** 400 `INVALID_AMOUNT`；无写入 | TC | P5 |
| AC-7.21 | 现金全是待查收款 | **Given** 在途 5000、可用 5000、现金 1000、待查收款 1000（批次已确认未到账，另有一笔认不出的入账）**When** 提现 1 **Then** 409，原因 `FREE_CASH`；单独证明公式扣了待查收款 | TC | P5 |
| AC-7.19 | 页面演示“钱没有重复动” | **When** 演示脚本让银行执行后丢回执，再恢复 **Then** 页面显示 PO1 `UNKNOWN → PAID`，附银行请求号和流水行；可用、在途、现金正确，银行侧只有一笔付款 | PW | P5 |

## AC-8 Stripe 测试模式

P6 的前提：用户已注册加拿大 Stripe 账号，密钥由用户本人放进 `.env`（本地）或密钥管理服务（部署）。design/stripe-integration.md 第 7 节的假设先逐条实测，结果回写设计文档。

| ID | 场景 | Given / When / Then | 层 | 阶段 |
|---|---|---|---|---|
| AC-8.1 | 密钥不进仓库 | **Then** 受版本控制的文件、Git 历史和交付产物里没有 `sk_`、`rk_`、`whsec_` 开头的值：gitleaks 用仓库里的 `.gitleaks.toml`（含 `whsec_` 规则，用合成样例验证过能报出）在 pre-commit 和 CI 都通过；只有空值的 `.env.example`。被 `.gitignore` 忽略的本地 `.env` 不在扫描范围 | CI | P1a |
| AC-8.2 | 只接受测试模式 | **When** 用 `sk_live_`/`rk_live_` 前缀的密钥启动 **Then** 应用拒绝启动。**When** 收到 `livemode=true` 的事件 **Then** 2xx；inbox 保留这条事件，状态 `REJECTED`、结果 `LIVEMODE`，开差异 `LIVEMODE_EVENT`；没有业务写入（无 payment、分录） | TC | P1b |
| AC-8.3 | 契约测试 | **Given** 录制并脱敏的真实 Stripe 夹具（webhook 和 API 响应）**When** 在 CI 里离线驱动适配器 **Then** 映射结果与预期一致；同一组场景在 fake Stripe 上产生的事件类型和所用字段与真实夹具一致 | TC | P6 |
| AC-8.4 | 真实 webhook 进入 | **Given** 测试模式端点（本地用 `stripe listen` 转发）**When** 流量生成器用测试卡完成 20 笔支付 **Then** 20 笔都 `CAPTURED`，签名都用真实 `whsec_` 验过；签名错误的请求 401 | TC | P6 |
| AC-8.5 | 真实的重复和乱序 | **When** 用 `stripe events resend` 重发、并经 chaos relay 复制和打乱真实事件 **Then** 每个业务事实只记一次账；不变量全部成立 | TC | P6 |
| AC-8.6 | 退款走真实 Stripe | **When** 对一笔测试支付发起退款 **Then** Stripe 上恰好一笔退款，`metadata.ledgerlab_refund_id` 对得上；任一成功证据（调用响应或退款事件）到达后 `SUCCEEDED`。**When** chaos relay 丢掉 `POST /v1/refunds` 的响应 **Then** 本地 `UNKNOWN`，查询后变 `SUBMITTED`/`SUCCEEDED`，Stripe 上仍只有一笔 | TC | P6 |
| AC-8.7 | 幂等键可能过期后不重复退款 | **Given** fake Stripe 上同一 charge 已有 12 笔退款，我们的 R1 在列表第二页；R1 的命令 `first_sent_at` 已超过 20 小时（快进本地时钟），fake Stripe 已清掉 R1 的幂等键 **When** 恢复 **Then** 翻完全部分页找到 R1，不再发送。**When** 查询的第二页超时 **Then** 命令保持 `UNKNOWN`，不发送。**When** 两个恢复 worker 竞争 **Then** 只有持有当前租约的那个能发送。说明：真实 Stripe 何时清理幂等键无法在测试里控制，这条只在 fake Stripe 上验收 | TC | P3 |
| AC-8.8 | payout 即批次 | **When** 测试模式产生一笔自动 payout，并到达 `payout.reconciliation_completed` **Then** 适配器拉到它的全部 balance transactions，批次净额 = payout 金额，逐行与本地记录相等，记 `BATCH_SETTLED`；模拟银行随后入账，批次 `BANKED` | TC | P6 |
| AC-8.9 | 对账接真实数据 | **When** 用 Stripe balance transactions / payouts 作外部数据运行对账 **Then** 结果可重放（输入存档、哈希、截止时点）；任何差异都能追到 Stripe 对象编号 | TC | P6 |
| AC-8.10 | 运行报告 | **Then** 每次真实沙箱运行产出报告：支付、退款、payout 数量，chaos relay 注入的重复/乱序/丢弃次数，不变量违反次数，fake Stripe 与真实 Stripe 的已知差异 | TC | P6 |

## AC-9 公开“来搞坏它”演示页

运行在模拟器模式（ADR-0017）。

| ID | 场景 | Given / When / Then | 层 | 阶段 |
|---|---|---|---|---|
| AC-9.1 | 一键动作 | **When** 访客依次点击：发一笔支付、重放上一条 webhook、批次抢先投递、并发两笔退款、提交后丢响应重试、银行执行后丢回执、杀掉 worker、导入带差异的结算单 **Then** 每个动作后页面在 2 秒内显示事件时间线更新，不变量面板 INV-1～INV-11 全部为通过，相应计数（被吸收的重复、被拒绝的超额退款等）增加 | PW | P7 |
| AC-9.2 | 不变量面板是真的 | **Given** 测试配置里打开一个故意破坏不变量的故障版本（例如去掉退款锁）**When** 跑并发退款动作 **Then** 面板显示失败并指出哪一条不变量；证明面板不是写死的 | PW | P7 |
| AC-9.3 | 白名单之外不可写 | **When** 在公开配置下调用纠错、取消退款、运营接口或任意故障钩子 **Then** 404；调查接口和 Agent 访问不到演示动作 | TC | P7 |
| AC-9.4 | 限流 | **When** 同一 IP 超过每分钟动作上限，或全站超过上限 **Then** 429，账本不受影响 | TC | P7 |
| AC-9.5 | 定时重置 | **When** 重置任务运行 **Then** 沙箱回到初始场景，不变量通过；重置前的运行摘要存档 | TC | P7 |
| AC-9.6 | 不收集个人信息 | **Then** 只记聚合访问量和动作次数，没有跟踪 cookie；对外数字只引用这份统计 | TC | P7 |
| AC-9.7 | 真实沙箱报告面板 | **Then** 页面只读展示最近一次 AC-8.10 的运行报告和运行时间 | PW | P7 |

---

---

## 附：演示脚本与验收条件对应

| 演示 | 对应验收条件 |
|---|---|
| 正常流程：请款 → 批次净额 → 银行入账 → 退款 → 提现 | AC-1.4、AC-1.11、AC-3.1、AC-5.1、AC-5.4、AC-7.1 |
| 重复回调，账本不变 | AC-2.3、AC-2.16 |
| 并发退款一成一拒；资金不足等待 | AC-3.2、AC-3.3、AC-3.15 |
| 提交后响应丢失 → 重试返回原结果 | AC-4.7、AC-4.12 |
| 银行已执行但回执丢失 → 恢复后无重复付款 | AC-7.8、AC-7.19 |
| 对账差异追到原始证据 | AC-5.7、AC-5.15 |
| 真实 Stripe 测试模式：真实 webhook 重复和乱序，账本仍正确 | AC-8.5、AC-8.10 |
| 访客自己动手搞坏它 | AC-9.1、AC-9.2 |
| Agent 解释一条差异并引用记录 | 不在本文件范围（P8，评测标准另写） |
