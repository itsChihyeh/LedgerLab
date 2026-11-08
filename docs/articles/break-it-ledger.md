# 破坏它试试：一个只追加账本，和它怎么证明自己没记错

> **草稿，给作者审，不对外发布。** 文中每个数字后面的括注是它在本仓库里的出处；没有出处的数字没有写。LedgerLab 是模拟项目，全部数据为合成数据或 Stripe 测试模式，没有真实用户、没有真实资金，文中也不出现用户数。

## 1. 问题：支付对账为什么难

替商户收款的平台，每天要面对三份互相独立、却必须对得上的“真相”：处理方（这里是 Stripe）说发生了什么，银行说收到了什么，我们自己的账本说记了什么。难点不在加减法，而在这三份真相到达的方式：

- webhook 会**重复**投递，会**乱序**，也会**丢**。结算批次的事件可能比它包含的支付事件更早到。
- 出站调用（退款、给商户提现）可能**结果未知**：请求发出去了，回执丢了。这时重发可能退两次款，不发又可能漏掉一笔。
- 两个并发的退款各自看到“还能退 60%”，都通过检查，合起来就超额。
- 手续费有取整，批次净额、银行入账金额、账上余额在分上差 1 分，对账就会报警。

LedgerLab 想回答的问题是：能不能把这些故障**当成一等输入**，让系统在每一种故障下仍然守住一组可以机器检查的不变量，并且让别人自己动手去“破坏”它来验证。

## 2. 设计

### 2.1 只追加的复式账本

金额一律是整数分（`long`／`BIGINT`），手续费取整规则写在 `docs/design/money-model.md`，并有手算样例。账本分录只追加、提交后封存：往一条已提交的分录里再塞 posting 会被数据库触发器拒绝，不是靠应用层自觉（`docs/adr/0003-sealed-entries-and-db-invariants.md`）。纠错不改历史，只走“冲销加重记”（`docs/adr/0011-restricted-correction.md`）。

约束放在数据库角色和触发器上：`migrator`、`app`、`readonly` 三个角色，`app` 角色没有对分录的 UPDATE／DELETE 权限（`docs/adr/0014-db-roles-and-threat-boundary.md`）。权限测试用 `app` 和 `readonly` 直连，证明越权操作确实被数据库拒绝，而不是只在应用里被检查（`AGENTS.md`）。

### 2.2 三层幂等，外加入站去重

重复请求会变成重复的资金效果，这是支付系统最常见的事故。我们按寿命分三层（`docs/adr/0009-three-layer-idempotency.md`）：API 幂等记录（保留 24 小时）、业务操作编号（`merchant_refund_ref` 等，永久）、分录 `source_key`（每个业务事实一个，永久）。入站 webhook 另按 `event_id` 去重（`docs/adr/0007-webhook-inbox.md`）。三层各防一类重复，任何一层单独都不够：24 小时后幂等记录被清理，同一个退款的重放仍然不能变成第二笔退款。

### 2.3 结果未知，不释放

退款和提现通过自建的窄命令表出站（`docs/adr/0008-outbound-command-table.md`）。命令与业务状态在同一事务里落库，结果未知时**先查询再重发**，并且不释放已预留的资金；Stripe 的幂等键只保证至少保留 24 小时，所以超过阈值后只有查询确认对方没有这笔退款才允许重发（同一份 ADR）。提现的“未知”状态单独建模（`docs/adr/0006-payout-unknown-outcome.md`）。

### 2.4 并发：一把资金锁

单商户下，所有改余额的事务先锁同一行 `funds_lock`，再按固定顺序锁其他行（`docs/adr/0004-concurrency-and-lock-order.md`）。代价是退款受理被串行，下面第 3.4 节有实测。

### 2.5 独立对账

`reconciliation` 模块只读账本，只写差异记录。它把我们的账本同 Stripe 的批次、模拟银行的流水分别核对，对不上就开一条差异，而不是去改账（`docs/adr/0010-reconciliation-matching.md`）。银行侧另有一条不变量：银行流水期初加入账减出账等于期末，且账上 1103 等于银行期末余额，否则差额必须显示（`docs/design/money-model.md` 第 5 节 INV-11）。

money-model 第 5 节一共列了 12 条不变量（INV-1 到 INV-12，`docs/design/money-model.md`），例如“每个分录借贷相等”“账户余额等于 posting 重算值”“每笔支付未失败退款合计不超过请款额”“同一 `source_key` 至多一个分录”。它们是后面所有证据的共同标尺。

## 3. 证据

### 3.1 测试

主应用在 P5 完成时有 1800 个测试，全部跑在真实 PostgreSQL 上，不 mock 数据库（`docs/handoff/p5.md`）。其中最有意思的是随机化模型测试：一个**不复用业务代码**的独立参考模型，和真实的 Spring 应用（签名 HTTP webhook → 真实数据库 inbox → worker）一起，被 jqwik 的有状态测试驱动着走随机动作序列，每一步都比较全部账户余额、退款状态、分录和 posting（`docs/testing/model-tests.md`）。

P3 阶段的 1000 条序列实测：24000 个生成动作、28267 个检查点、0 次违反、0 次清理失败，其中 100 次“用输入日志在空库重放并比对快照”的重建全部一致（`docs/testing/model-tests.md`，“T13 实测”）。测试里还注入了两类崩溃，分别发生在领取之后（668 次）和业务写入之后（623 次，同上）。

测试自己也要被测试。作者故意把参考模型里“跳过资金不足的队首退款”的判断改错，随机测试在第 4 条序列就失败，收缩后定位到具体规则（`docs/testing/model-tests.md`，“T13 有效性演示”）。同类实验在 T15 里还有一次。这些故障代码都没有提交。

要注意这份证据的边界：模型测试不是并发证明，并发由栅栏和受控交错的专项测试负责；提现、纠错的覆盖也在后续阶段补齐，不能把这一节的数字当作全系统的覆盖率（`docs/testing/model-tests.md`）。

### 3.2 真实 Stripe 测试模式运行

P6 把处理方换成真实的 Stripe 测试模式。已经完成的部分有：20 笔真实测试支付经 `stripe listen` → chaos relay → 主应用，全部进入 `CAPTURED`；篡改正文、缺签名头、过期时间戳的请求都被 401 拒绝；不变量违反数为 0（`docs/handoff/p6-pause.md`，T04）。随后用 Events API 对照加 `stripe events resend` 补投，再叠加 relay 复制 3 份和扣留乱序：9 个事件各只产生一次业务结果，每个 charge 一笔 payment 一条分录（同一文件，T05）。整个沙箱里一共创建了 28 笔测试支付、0 笔退款（同一文件）。

**待完成：** 真实 payout 作为批次、退款走真实 Stripe（含丢回执）、对账接真实数据（AC-8.8、AC-8.6、AC-8.9），以及运行报告 `docs/runs/`，都依赖 Stripe 第一笔自动 payout，暂停时 payout 列表仍为空（`docs/handoff/p6-pause.md`）。所以这篇文章里没有“真实 Stripe 退款”或“真实批次对账”的结论，等这部分跑完再补。

### 3.3 破坏它试试：公开演示页

演示页（`docs/adr/0017-public-break-it-demo.md`、`docs/design/demo-and-delivery.md`）把上面的故障做成 8 个按钮：随机金额支付、重放 webhook、批次早于支付到达、并发双退款、退款回执丢失、银行提现回执丢失、worker 中途崩溃、导入一份带错行的银行流水。每个动作结束后页面显示 12 条不变量（INV-1～INV-12）是否通过，失败的那一行变红并指出对象（`docs/design/demo-and-delivery.md` 第 2、17 节）。

“不变量面板是真的”有一个对照实验：测试专用开关让退款受理跳过支付行锁和额度复查，Playwright 在这个故障版本上点“并发双退款”，面板必须显示 INV-7 失败并指出支付编号（`docs/design/demo-and-delivery.md` 第 6、17 节，AC-9.2）。也就是说，面板不是一直亮绿灯的装饰品。

仓库附带一段自动录制的演示视频：脚本依次点 8 个动作、每步停 2 秒，最后停在不变量和计数上（视频不放在仓库里，生成命令见 `docs/media/README.md`）。

### 3.4 性能基线

k6 基线（2026-11-05，3 轮取中位数，负载发生器与被测栈在同一台 12 个逻辑核的 Windows 机器的同一个 Docker VM 里，数字适合前后对比，不是独立压测机上限；`docs/perf/baseline-2026-11-05.md`）：

- **webhook 入站**：50／100／200 rps 三档，实测速率与目标一致，2xx 以外为 0.00%；p99 分别为 6.32／5.67／5.46 ms。入站只验签并写一行 inbox。
- **后台处理才是瓶颈**：默认配置下 worker 处理速度约 18.3～18.5 条／秒，200 rps 档负载结束时积压 21805 条，180 秒上限内没有排空（同一文件，W1）。这个数字说明默认参数下入站和处理是两件事，要调高上限得改 `ledgerlab.inbox.batch-size` 或轮询间隔，基线没有测。
- **单商户退款受理**：1 并发 106.1 TPS（p95 14.64 ms），20 并发 144.6 TPS（p95 204.82 ms）。20 倍并发只换来 1.36 倍吞吐，其余都变成了排队延迟，这和 ADR-0004 预期的“单商户资金锁串行”形状一致；基线没有单独测锁等待，这是由吞吐和延迟的形状推断的（同一文件，W2 与瓶颈分析）。
- **只读查询**：50 并发、60 秒，总吞吐 355.7 请求／秒，错误率 0.00%（同一文件，W3）。
- 每个栈每次测量后都调了不变量接口，共 12 次，全部通过（同一文件）。

## 4. 局限

这是一个作品集规模的模拟系统，下面几点必须说清楚：

1. **单商户。** 所有改余额的操作竞争同一把商户资金锁，性能数字（尤其退款受理）只代表单商户。按商户分片只是设计上的方向，基线没有多商户场景，不能由此推出多商户的数字（`docs/perf/baseline-2026-11-05.md`）。
2. **银行是模拟的。** 银行流水由模拟银行生成，即使在真实 Stripe 测试模式下，模拟银行也只是把 Stripe 的 payout 当成收到的转账（`docs/adr/0016-stripe-test-mode-and-simulator-roles.md`）。银行侧的对账能力证明的是逻辑，不是对真实银行文件格式和延迟的兼容。
3. **测试模式下 webhook 和结算单同源。** Stripe 测试模式里，事件和 balance transactions／payout 出自同一个系统，作为“独立的第二份真相”独立性较弱（同一份 ADR 的后果一节）。
4. **评测数字只在模拟器模式下有真值。** 对账准确率之类需要“知道真相”的评测，只有 fake Stripe 加模拟银行能给出真值；真实沙箱只能产出运行报告，不能当作准确率（同一份 ADR）。
5. **还没做完的部分：** P6 的真实 payout、真实退款、对账接真实数据尚待完成（见 3.2）；调查 Agent 等后续阶段不在本文范围。
6. **性能数字的口径：** 单机、同机压测，一台 Windows 机器；摘自 `docs/perf/baseline-2026-11-05.md`，不构成容量规划。

## 5. 复现

演示站、基线和视频都可以一条命令复现：`sh scripts/demo/up.sh`（`docs/design/demo-and-delivery.md` 第 1 节）、`sh perf/run.sh`（`docs/perf/baseline-2026-11-05.md`）、`cd frontend && npm run video:demo`（见 `frontend/e2e/demo-video.spec.ts`）。

---

## English summary (≈300 words)

**Break it: an append-only ledger and how it proves it hasn't miscounted.** *Draft for the author's review; not for publication. All data is synthetic or Stripe test mode, with no real users or money.*

LedgerLab simulates a small payment platform that collects money for merchants. The processor (Stripe test mode, or a fake Stripe) reports payments, settlements and refunds through webhooks. The system books them in an append-only double-entry ledger and independently reconciles against processor batches and a simulated bank.

The hard part is not arithmetic but arrival: webhooks are duplicated, reordered or lost; outbound refunds and payouts can have an unknown outcome; two concurrent refunds can each look valid and together over-refund. The design treats these as first-class inputs. Amounts are integer cents. Ledger entries are sealed after commit by database triggers and role permissions, not by application checks. Idempotency has three layers (API key, permanent business reference, ledger source key) plus inbound event de-duplication. An unknown outbound outcome never releases reserved funds: the system queries first and resends only if the processor confirms it has no such operation. Reconciliation is read-only on the ledger and only writes discrepancy records. Twelve invariants (INV-1 to INV-12) serve as the common yardstick.

Evidence comes from four sources, each cited to a repository file in the Chinese text: a randomized model test where an independent reference model drives the real application (1000 sequences, 24,000 actions, 0 violations; the test itself is validated by deliberately breaking the model); a Stripe test-mode run (20 real test payments captured, tampered signatures rejected; real payouts and refunds are still **pending**); a public "break it" demo page with eight failure buttons and a live invariant panel, proven real by a deliberately broken refund lock that turns INV-7 red; and a k6 baseline showing refund acceptance serialized by the single merchant lock (106.1 TPS at one client versus 144.6 TPS at twenty).

Limitations: single merchant; simulated bank; in test mode webhooks and settlement statements come from the same source; accuracy-style numbers have ground truth only in simulator mode; benchmarks come from one machine with the load generator sharing the machine.
