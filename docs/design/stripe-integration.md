# Stripe 测试模式接入

P0 设计稿 v0.2，2026-10-21（同日开工前审查修订）。决策见 ADR-0016。

目的：让处理方一侧是真实的支付服务商沙箱，而不只是我们自己编的世界。webhook 的重复投递、乱序和签名都来自真实 Stripe；对账用的外部数据来自 Stripe 的 balance transactions 和 payout。全程只用测试模式，不涉及真钱。

---

## 1. 谁扮演什么

| 角色 | 测试 / CI / 故障演示 | 真实沙箱运行 |
|---|---|---|
| 处理方 | **fake Stripe**：模拟器里说 Stripe 协议子集的部分（webhook 格式、签名方案、少量 REST 接口），状态和真值都在 `sim_processor`。它按 Stripe 的规则维护**待结算余额和可用余额**：退款只用可用余额，不够时返回 `pending`；payout 明细先 `in_progress` 再 `completed` | **Stripe 测试模式**（用户自己的账号） |
| 进入 LedgerLab 的路径 | 同一个 Stripe 适配器：`POST /webhooks/stripe` ＋ 出站 Stripe REST 客户端 | 同左，只换 base URL 和密钥 |
| 投递故障注入 | **chaos relay**：挡在 webhook 入口前，原样转发、复制、延迟、乱序、丢弃（请求体不改，签名仍然有效；改请求体则签名失败）。原样延迟不超过 240 秒，留在 5 分钟签名窗口内；更长的延迟用“处理方重新投递”模拟（fake Stripe 重新签名，和 Stripe 自己重试一样） | 同一个 chaos relay，挡在 `stripe listen` 或公网入口前；再加 Stripe 自己的重试和 `stripe events resend` |
| 出站故障注入 | 模拟器直接按故障计划超时、丢回执 | chaos relay 的出站代理：请求照发给 Stripe，把响应丢掉，制造“对方已执行、我们不知道” |
| 银行 | **模拟银行**（`sim_bank`），首版没有真实银行可接 | 仍是模拟银行；它把 Stripe payout 当成“收到的转账”，自己决定入账日期，并可注入拆分、重复、少到 |
| 产生支付的人 | 模拟器按场景生成 | **流量生成器**：用 Stripe API 和测试卡创建并确认 PaymentIntent |
| 对账的外部证据 | fake Stripe 的 balance transactions / payouts 接口 | 真实 Stripe 的 balance transactions / payouts 接口 |
| 真值（ground truth） | `sim_truth`：注入了什么、预期什么差异 | Stripe API 的状态是处理方侧的事实；chaos relay 日志记录它做了什么；**没有**“注入差异”的真值 |

P1b relay 提供 `POST /relay/stripe` 和 `PUT/GET /sim/relay/plan`，下游由 `sim.relay.target-url` 指定；规则顺序匹配事件号/类型，默认 PASS。HOLD_AND_REORDER 等后续 k 个事件完成转发后释放；扣留满 240 秒仍未凑够则自动原样转发，日志记 `HOLD_TIMEOUT_RELEASE`。DELAY 与扣留超时使用可控模拟时钟及内存调度队列；进程重启不保留计划和待发请求。完整计划格式见 `simulator/README.md`。

P2 T03 fake Stripe 增加 charge 的 balance transaction、payout 生命周期与只读 REST；协议标记仍固定 `2026-09-30.endive`。字段对照官方 [Payout](https://docs.stripe.com/api/payouts/object)、[Balance Transaction](https://docs.stripe.com/api/balance_transactions/object)、[列表分页](https://docs.stripe.com/api/balance_transactions/list)。`sim.stripe.availability-delay` 默认 PT0S，按模拟时间决定 charge 净额何时可用；payout 默认归集全部可用且未分配的 CAD 行，另列一条负向 `type=payout`（主应用跳过）。失败不重用原支付行，而是新增 `payout_failure`、`source=原 po_`、`net=返还金额`，在 Payout 的 `failure_balance_transaction` 中引用，供下一批次使用（样例 H）。退款执行接口留在 P3。

`POST /sim/payouts`、`/{id}/reconcile`、`/{id}/paid`、`/{id}/failed` 分别生成完整对象快照事件；相同转换重试返回已有事件号，不重复投递或返还资金。标准自动 payout 的明细从 in_progress 转 completed；手动或 instant 的 reconciliation_status 为 not_applicable，拒绝 reconcile。允许 paid 后再收到 failed，failed 后不允许 paid；reconcile 独立于打款状态。默认 arrival_date 为创建时刻加 24 小时，仅是模拟器预计日期，不复刻 Stripe 银行假日日历、不驱动银行入账。

只读接口为 `GET /v1/payouts/{id}`、`GET /v1/payouts?created[gte]=…&created[lt]=…`、`GET /v1/balance_transactions?payout=…`，Bearer 必须精确匹配 `sim.stripe.api-key`（仅 test 前缀，未配置时只读 API 全部 401）。排序为 created DESC、id DESC，同秒 id 顺序是模拟器固定规则；limit 默认 10、范围 1～100，无效/不属于筛选结果的游标返回 400。为便于检查，fake 的行接口在 in_progress 时也可读；适配器仍必须等 completed，不能据此放宽第 2 节契约。

手续费覆盖与 payout/行/分页故障在 `sim_processor.fault_plan` 追加存档；重复行故障把副本紧跟在本页原行后，故意使该页最多多一行，游标按未复制集合推进，不会因相同 id 陷入循环。未知 charge/adjustment 行只污染指定 payout 的明细，不重新计算 payout 金额。`POST /sim/clock/advance {"seconds":n}`（非负整数）推进模拟时钟并同步执行到期 relay 回调；`GET /sim/clock` 返回 ISO-8601 `now`。时钟不改变现实签名时间，快进操作仍应由单一场景驱动者执行。

两点要如实写进 README：

- 测试模式下结算单和 webhook 都来自 Stripe，两者的独立性比模拟器模式弱（真实对账也是如此，处理方的报表就是处理方的话）。对账准确率（误报、漏报）的评测只在模拟器模式做，那里有真值。
- fake Stripe 只实现我们用到的子集，行为以真实 Stripe 为准；两者的差异靠契约测试抓（第 6 节）。

## 2. 事件映射

webhook 端点只订阅下表的事件类型。

**当前实现（P3 T09）**：`charge.succeeded` 调用请款服务；`payout.created`、`payout.reconciliation_completed`、`payout.paid`、`payout.failed`、`payout.canceled` 经 ingestion 调用 settlement；订阅列表同时启用 `refund.created`、`refund.updated`、`refund.failed`，经 `RefundEvidence.apply` 处理。`APPLIED`、`DUPLICATE_FACT` 对应 inbox `PROCESSED`，退款 `CONFLICT` 映射 `REJECTED / BUSINESS_CONFLICT`，不重复开差异。payout 的依赖未到可以 `PARKED`，退款不走停放路径。`charge.dispute.*` 为 `REJECTED / UNSUPPORTED_TYPE` 并开 `UNSUPPORTED_EVENT`；未订阅的其他类型为 `IGNORED`、`result=NULL`。所有类型的 `livemode=true` 优先拒绝为 `LIVEMODE` 并开一条 `LIVEMODE_EVENT`；charge 交给请款服务判定，其他类型在分流前判定；退款对象自身若带 live 标志，也优先拒绝。

Charge 的 `amount` 和 `amount_captured` 只接受可表示为 `long` 的 JSON 整数；小数、字符串、非数字或缺失映射为 `null`，交由请款准入判定。其余字段按 Charge 取值，`receivedAt` 使用 inbox 接收时间。worker 默认租约 30 秒、每 1 秒轮询最多 20 条、失败从 1 秒开始倍增并封顶 5 分钟，超过 5 次进入 `DEAD`（第 6 次失败）；配置与三个事务的恢复规则见 state-machines 第 5 节。

| Stripe 事件 | 我们的处理 | 分录 |
|---|---|---|
| `charge.succeeded` | 通过请款准入检查后支付 `CAPTURED`；`processor_payment_ref` = `ch_…`，同时存 `pi_…` | `PAYMENT_CAPTURED` |
| `refund.created`、`refund.updated`、`refund.failed` | **按事件里退款对象的 `status` 处理，不按事件名**：`pending` 只记状态历史；`succeeded` → 退款 `SUCCEEDED`；`failed`/`canceled` → 退款 `FAILED`。按 `metadata.ledgerlab_refund_id` 找本地退款 | 见左：`REFUND_EXECUTED` / `REFUND_FAILED` |
| `payout.created` | 批次 `ANNOUNCED`：记下 payout 存在和金额，做 payout 准入检查；明细还不拉 | — |
| `payout.reconciliation_completed` | 明细就绪：拉取 `GET /v1/balance_transactions?payout=po_…` 全部分页作为批次行，核对后批次 `CONFIRMED` | `BATCH_SETTLED` |
| `payout.paid` | 状态历史（处理方说“应该到了”），不动现金 | — |
| `payout.failed`、`payout.canceled` | 批次 `PAYOUT_FAILED` | `BATCH_PAYOUT_FAILED` |
| `charge.dispute.*`、其他资金类事件 | 不支持：inbox `REJECTED(UNSUPPORTED)`，开差异，页面可见 | — |
| 未订阅的其他类型 | `IGNORED`（记录但不处理） | — |

- 不在 Stripe 里找不到 `metadata.ledgerlab_refund_id` 的退款（例如有人在 Stripe 后台手动退款）：`REJECTED(UNKNOWN_REFUND)`，开差异 `MISSING_IN_LEDGER`。首版不支持从处理方后台发起的退款。
- `livemode=true` 的事件一律拒绝并告警。
- **请款准入**：`charge.succeeded` 里的 Charge 必须同时满足 `status=succeeded`、`captured=true`、`amount_captured = amount`、`currency=cad`、`livemode=false`、金额在 50～99999999 分之间；否则 inbox 结果 `REJECTED`（原因 `UNSUPPORTED_CAPTURE`、`UNSUPPORTED_CURRENCY`、`INVALID_AMOUNT` 或 `LIVEMODE`），不记账。流量生成器只用自动请款的 PaymentIntent。
- 请款准入按 `LIVEMODE` → `INVALID_AMOUNT` → `UNSUPPORTED_CURRENCY` → `UNSUPPORTED_CAPTURE` 的优先级检查，只报告第一个失败原因；金额缺失也算非法金额，`cad` 大小写不敏感。准入失败以事件为主体（`subject_type=event`、`subject_id=eventId`）开 `INGESTION` 差异：`LIVEMODE` 对应 `LIVEMODE_EVENT`，其余对应 `UNSUPPORTED_EVENT`，不写 payment、状态历史或账本。
- 请款服务在调用方事务中先取资金锁，再做准入和按 Charge 编号锁定支付。同金额的已有事实返回 `DUPLICATE_FACT`，不写入；金额不同返回 `BUSINESS_CONFLICT`，开 `EVENT_BUSINESS_CONFLICT`，expected/actual 分别保存已记金额和新金额。新事实的支付、`NULL → CAPTURED` 历史和 `PAYMENT_CAPTURED` 分录一起提交。
- **payout 准入**：`automatic=true`、`method=standard`、`currency=cad`、`amount > 0`。不满足的（手动 payout、即时 payout、从银行扣款的负 payout）→ 批次 `HELD(UNSUPPORTED_PAYOUT)`，开差异。
- **明细就绪**：只在 `reconciliation_status=completed` 后拉取批次行。收到 `payout.created` 后如果迟迟等不到 `payout.reconciliation_completed`，后台按退避查询 payout 对象，看到 `completed` 也一样处理；`not_applicable` 按不支持隔离。拉到的明细必须分页拉完、合计等于 payout 金额，才形成批次版本；未就绪或没拉完的抓取只存档，不参与版本冲突判断。
- 一个退款的“成功证据”可能来自四处：出站调用的响应、`refund.created`、`refund.updated`、查询结果，外加结算批次里的退款行。它们都走同一个状态转换入口，争同一个 `refund:{refund_id}:executed`，先到者记账。
- 批次行的 balance transaction 类型：`charge` → `PAYMENT`，`refund` → `REFUND`，`payout_failure` → `PAYOUT_FAILURE`；其他类型（`adjustment`、`dispute` 等）→ 批次 `HELD(UNSUPPORTED_LINE)`。

拉取批次行是 HTTP 调用，在数据库事务**之外**做：worker 先拉取并把原始 JSON 存进 `io.processor_fetch`，再开事务核对和记账。拉取失败按 inbox 的重试规则退避。

### P3 T09 实现备注：退款 webhook

- `RefundParser` 只接受 1～99999999 分、可表示为 long 的 JSON 整数；缺失、null、小数、指数、字符串、非数字及溢出为 `INVALID_AMOUNT`。币种忽略大小写，仅 CAD；缺失／其他币种为 `UNSUPPORTED_CURRENCY`。缺失或非法 `re_`、`ch_`、status、非字符串且非 null 的 failure_reason 为 `UNSUPPORTED_TYPE`。failure_reason 可缺省／null。上述准入失败均以事件为主体开 `UNSUPPORTED_EVENT`；live 优先于所有其他检查。
- metadata 缺失、类型不符、不是本地编号或找不到对应退款时，`REJECTED / UNKNOWN_REFUND` 并开 `MISSING_IN_LEDGER`（subject `refund`、处理方 `re_`；actual 保留 charge、整数金额和 metadata 编号）。即使 `re_` 已知也不回退匹配，以免接纳处理方后台发起的退款。
- `ProcessorRefundFact` 增加可选 amount／chargeRef，保留原七参数构造器供已核对身份的命令／查询调用者使用；webhook 总是传入两字段。统一入口取资金锁、支付锁、退款锁之后，先核对金额和 charge，再判状态和幂等；不一致只开一条 `REFUND_STATE_CONFLICT`，不更新退款、不记账。复用既有 `PaymentRefunds.processorRef`，未新增跨模块数据库写入或依赖。
- pending 按 T07 入口让 READY／UNKNOWN → SUBMITTED，只写退款历史，不记分录；重复 pending／终态后的迟到 pending 为 DUPLICATE_FACT。三种事件名都由对象 status 决定结果。WEBHOOK 的证据编号为原 evt_，历史与执行／失败分录 occurred_at 均取事件 created，绝不取退款对象 created；记录时间取注入 Clock。
- 本地退款及命令在发送前已提交，所以不存在等待退款落库的 PARKED 路径。重投同 event_id 只新增投递记录，不重新运行已拒绝的业务；同内容新 event_id 会按处理时的本地事实重新判断，原拒绝与差异保留。空库重放须先恢复退款受理／命令，再输入这些 webhook，不能依赖停放补齐顺序。
- 固定合成夹具 `src/test/resources/contracts/refund-updated-failed.json` 与模拟器同名夹具逐字节一致。主应用验证全部映射字段和事件时间，模拟器生成失败退款事件后仅规范化生成的编号与时间，逐字段比较完整 JSON。夹具不是实际 Stripe 录制，P6 验证范围不变。

### P3 T14 实现备注：三进程冒烟

- `scripts/smoke-p3.sh` 先复用 P2 的 S3 与全部对账断言，再按样例 A 退 P1 2000：应用出站 worker 实际调用 fake Stripe，`refund.created` 经 relay DUPLICATE 两次送达；等待命令 DONE 和 inbox PROCESSED 后验证一次执行、API 同键原结果重放，以及 B2 可用 12505／在途 2825。
- 第二笔对 P2 退 3000，选任务允许的 `force_pending` 路径；补充处理方资金后清故障并 process，验证 SUBMITTED → SUCCEEDED。未引入提现或改动商户 FIFO 门槛。随后补齐 B2／B3 的银行流水和处理方快照，ALL 对账断言零发现、零差异且账本不变；R2 的成功 webhook 业务时刻必须早于 cutoff，避免退款检查被空范围掩盖。
- 业务时钟沿用 P2 的历史日期，P3 截止固定为 2026-10-24 多伦多 00:00，应用时钟与默认 60 秒证据宽限不改。R1 的命令响应与 webhook 仍正常竞争，首个成功证据决定 occurred_at；R2 的执行固定由 process 后的历史 webhook 驱动。
- 冒烟观察端只用 simulator 角色 SELECT 退款事件编号，再通过现有 HTTP 查询核对两端投递与状态；不读取 sim_truth，不把模拟器库访问加入主应用。共享起停、HTTP 和有界条件等待，smoke-p2 的场景与断言保持原样；两个脚本各用独立 Compose 项目并在退出时删除其卷。

### P3 T10 实现备注：批次行关联退款

- 完整拉取 balance transactions 后，refund 行 source 为 re_、金额为负。已知 re_ 直接交批次核对；未知 re_ 在业务事务外调用 T08 `ProcessorRefundGateway.retrieve`，该网关沿用 REFUND_QUERY 独立存档，再把 metadata.ledgerlab_refund_id、退款本金和 charge 身份交给核对事务复查。查询对象的当前状态不替代批次行成功证据，退款执行时间仍取行 created。
- HTTP／解析失败（包括 T08 现有的直接 retrieve 404 不确定语义）走 inbox 重试；成功取回但无本地 metadata／本地退款不存在则 HELD(UNKNOWN_REFUND)。成功查询的 metadata、charge、amount 与本地身份冲突时不能绑到另一笔退款。
- UNKNOWN_PAYMENT 批次因请款到达而重核时，不在资金事务内发 HTTP：使用首次 UNKNOWN_PAYMENT 历史时点之前的完整 REFUND_QUERY 存档恢复身份；已有 re_ 仍优先按本地关联核对。原明细仍固定使用 lines_fetch_id。P4 T03 将此无 HTTP 重核路径移入 settlement.BatchRechecks；金额纠错另触发 HELD(AMOUNT_MISMATCH) 重核，历史退款行批次由运营接口按同一存档规则重核，见 state-machines 第 4 节。

### P2 T05 实现备注：就绪拉取与兜底轮询

- `PayoutProcessing` 复用 T04 的 Payout 解析与 `StripeReader`，传给 settlement 的证据类型不依赖 ingestion。created 已带 `completed` 时也拉取；`in_progress` 不拉；不支持的 payout 直接隔离，不拉明细。`type=payout` 的转出行跳过，charge 和已知失败打款退回行参加核对，其余行暂隔离。拉取未完成只保留 `complete=false` 存档，worker 重试，不形成批次版本。
- `occurred_at` 和分录时间取 payout `created`；`settlement_date` 取其多伦多日期；`arrival_date` 保留处理方 epoch 对应的 UTC 日期，不用它驱动银行入账。正常批次的五条 posting 按 money-model 第 3 节生成，零金额省略；只有失败打款退回时为两条。
- `SettlementPolling.poll()` 查询本地仍为 `ANNOUNCED`、记录时间已超过 `ledgerlab.settlement.after`（默认 30m）的批次。按原始 `source_event_id` 关联的 `purpose=PAYOUT` 拉取存档统计次数及最近 `fetched_at`，退避从 `initial-backoff=1m` 倍增到 `max-backoff=30m`；进程重启后仍可从数据库恢复退避，无新增迁移。`poll-interval` 默认 1m。`scheduling-enabled` 默认继承 `ledgerlab.inbox.scheduling-enabled`，已有关闭 inbox 调度的测试不会另起后台轮询；也可显式配置 `ledgerlab.settlement.scheduling-enabled` 独立控制，测试直接调用 `poll()`。
- 轮询查询和明细拉取均在业务事务外；看到 `completed` 后共用同一核对／记账入口，`not_applicable` 则 `HELD(UNSUPPORTED_PAYOUT)`。查询、分页或业务处理失败后按已存档尝试继续退避；不伪造 inbox 事件。历史引用 `processor_fetch`，批次原始事件外键保留。并发轮询或 webhook 可重复读取，但资金锁、批次状态检查及 `source_key` 保证业务只应用一次。
- T05 的 HELD 分类与恢复范围、paid 的 PARKED／唤醒、事务失败回到 ANNOUNCED 的约定见 state-machines 第 4 节实现备注。HTTP 已完成并不代表批次业务确认，T04 的存档契约不变。

## 3. 出站调用

| 用途 | 接口 | 幂等与恢复 |
|---|---|---|
| 提交退款 | `POST /v1/refunds`（`charge`、`amount`、`metadata.ledgerlab_refund_id`） | `Idempotency-Key` = 退款号 `rfd_…`；响应里的 `status` 当作退款事实处理（第 2 节） |
| 查询退款 | 已知 `re_…` 时 `GET /v1/refunds/re_…`；不知道时 `GET /v1/refunds?charge=ch_…` **翻完全部分页**（直到 `has_more=false`），按 metadata 找 | 结果未知时先查再重发 |
| 批次行 | `GET /v1/balance_transactions?payout=po_…`（分页） | 只读 |
| 对账导入 | `GET /v1/payouts?created[gte]&created[lt]`，再对每个 payout `GET /v1/balance_transactions?payout=po_…` | 只读；按 payout 创建区间取数（contracts 第 4 节），原始结果存档并算哈希 |

**Stripe 的幂等键至少保留 24 小时，之后可能被清理**（Stripe idempotent requests 文档）。所以退款命令在 `UNKNOWN` 状态下的规则是：

1. **任何重发之前先查询**。距第一次发送（命令上不可变的 `first_sent_at`）超过 20 小时的命令，只有查询**完整成功**并确认 Stripe 上没有这笔退款时才允许重发。
2. “完整成功”指：按编号直接查到结果，或列表翻完全部分页且每一页都成功。任何超时、5xx、分页中断都不等于“不存在”，命令保持 `UNKNOWN`。
3. 重发只能由持有当前租约的 worker 做；租约过期后旧 worker 的回执不能覆盖新结果（[state-machines.md](state-machines.md) 第 6 节）。

否则幂等键过期后的重发可能变成第二笔退款（例如原退款在列表第二页，只查第一页就会以为不存在）。

### P3 T07 实现备注：退款网关契约

- `refunds.ProcessorRefundGateway` 声明 create(requestId, chargeRef, amount, refundId)、retrieve(reRef)、findByRefundId(chargeRef, refundId)，返回 Found／NotFound／Incomplete／Rejected 及 HTTP 状态、原文、安全错误码。findByRefundId 的 NotFound 必须意味着全部分页查询完整成功；任何分页中断返回 Incomplete。Rejected 仅用于明确证明退款未创建的拒绝，5xx／断线／超时不能映射为 Rejected。实际 HTTP／分页解析由 T08 实现，T07 以独立内存网关验证调用顺序和恢复规则。
- 首次且未被接管的命令直接 create；有 first_sent_at 或重试／接管标记时先查询。优先取退款行已知的 re_，其次取命令 external_ref，否则完整列表查询 metadata。Found 作为 COMMAND_QUERY 统一应用并完成命令，Incomplete 保持 UNKNOWN；仅 NotFound 允许同 request_id 重发，发送前重新检查当前租约。阈值 `ledgerlab.outbound.refund-resend-after` 默认 20h；尝试摘要分别记录 FIRST_SEND、COMPLETE_NOT_FOUND_WITHIN_THRESHOLD、COMPLETE_NOT_FOUND_AFTER_THRESHOLD，恰好 20h 属于阈值内。
- create 的退款对象作为 COMMAND_RESPONSE 统一应用，pending 也使命令 DONE（后续退款状态靠 webhook／查询／批次证据）；明确拒绝以无 re_ 的失败事实释放，原因保存处理方错误码。调用或查询得到状态时以注入 Clock 的接收时刻为 occurred_at，不使用对象 created。命令证据引用 `{command_id}:lease:{epoch}`，可关联同租约下查询与创建的只追加尝试。
- first_sent_at 在首次网关操作前短事务写入；如果原 worker 在发送前崩溃，接管者的第一次查询也是首次外部调用。该时刻此后不变，退款 submitted_at 优先沿用它；初次查询后若确认不存在，重发仍使用原 request_id。短事务提交与 HTTP 发送之间的进程崩溃无法原子化，接管者始终先查。

### P3 T08 实现备注：退款 HTTP 与过期恢复

- `ingestion.internal.StripeRefundAdapter` 实现消费者定义的 `ProcessorRefundGateway`；与 `RestStripeReader` 共用 RestClient bean、JDK HTTP 配置、`StripeHttpTransport` 和 `StripeFetchArchive`。ingestion 显式声明对 refunds 及其返回类型所在 outbound 公开 SPI 的依赖，不访问两者 internal 包；既有模块校验继续执行。
- create 用 form 的 charge、amount、`metadata[ledgerlab_refund_id]`，固定 request_id 作为 Idempotency-Key。解析 id、status、正 long 整数 amount、charge、metadata 和可空 failure_reason；金额、charge、metadata 与本地命令不符时保持 UNKNOWN，不应用金额事实。HTTP Found 增加 Details，Rejected 增加 receivedAt，保留旧构造方式供既有内存网关使用。状态证据取对应响应正文读完时的 Clock（列表取匹配对象所在页的接收时刻），不取对象 created 或后续存档／写回时刻。
- 列表始终带 limit=100 并翻到 has_more=false，首屏找到也继续。任一页失败、非法形状、空页仍有后页或重复游标／对象编号均为 Incomplete；不同处理方编号的多笔匹配时 Incomplete 并通过 reconciliation 公开入口独立记录 REFUND_STATE_CONFLICT，证据引用本次 processor_fetch。GET 的 4xx（包括 404）也视为 Incomplete，只有完整列表无匹配才返回 NotFound。
- 2xx 非法 JSON／金额／不支持状态、超时、断线、5xx、重定向保持不确定；POST 的结构化 Stripe 4xx 明确拒绝保存安全错误码。上游正文只保留在受限证据字段，不拼入异常、错误码或日志；不合规或带密钥的错误码降为 PROCESSOR_ERROR。无密钥不发 HTTP，仍追加失败存档。
- HTTP 在 NOT_SUPPORTED 内执行，processor_fetch 以 REQUIRES_NEW 提交；每次网关调用一行，分页原文沿用 T04 的 JSON 字符串数组，request.attempts 留存各页查询和状态。POST 存 form 与 Idempotency-Key，不存 Authorization。V17 仅扩展 purpose CHECK；完整拒绝也记 complete=true，complete 不代表已记账。
- `RefundHttpOutboundTest` 用 JDK HttpServer + 真实 PostgreSQL 覆盖 AC-8.7 三段（12 笔、第二页、25h 后清键、第二页超时、查询后发送前租约接管），以及 AC-3.10 的十次 HTTP 超时、AC-3.11 断线并写回前崩溃、AC-3.17 调用成功和 AC-3.18 调用 pending。超时按 T06 的九次创建前故障加一次创建后丢回执注入，随后查询恢复且处理方只有一笔；老化差异与 webhook 路径不属于 T08。适配器专项测试另覆盖存档、分页拒绝、解析和凭据安全；全部只连 loopback 替身。

### P2 T04 实现备注：只读客户端与拉取存档

T10 采用消费者定义接口的依赖方向：`reconciliation.ProcessorSnapshotReader` 只声明快照所需的两个读取；`ingestion.internal.ProcessorSnapshotAdapter` 委托 `StripeReader`，固定使用 RECON_SNAPSHOT 并作为 bean 装配。reconciliation 不依赖 ingestion，既有 Modulith 边界不变；完整快照由 reconciliation 汇总并归档，单个 fetch 不等于完整快照。

- 公开入口为 `dev.ledgerlab.ingestion.StripeReader`，提供 `retrievePayout`、`listBalanceTransactionsForPayout`、`listPayoutsCreated`；其嵌套类型 `FetchPurpose` 对应 V6 的 `PAYOUT`、`BATCH_LINES`、`RECON_SNAPSHOT`。`FetchResult<T>` 带存档编号、原文哈希、`fetchedAt`、不可变的解析列表和安全失败原因；`complete()` 仅表示这一次 HTTP 查询及解析完整成功，不表示 payout 明细已就绪、金额已核对或整个对账快照已完整。
- 使用现有 Spring `RestClient` 与 JDK HTTP 客户端，不引入 SDK 或其他依赖。`ledgerlab.stripe.api-base-url` 默认 `https://api.stripe.com`（可用 `STRIPE_API_BASE_URL` 覆盖）；`connect-timeout` 默认 `5s`、`read-timeout` 默认 `30s`，均须为正。只接受 HTTP(S) base URL，禁止 URL 内嵌凭据、查询串和 fragment；禁用重定向，3xx 作为失败存档。每个请求发送 Bearer 密钥及固定 `Stripe-Version: 2026-09-30.endive`。
- 沿用原有密钥启动校验：空密钥不影响 webhook 入站；开始拉取时返回 `complete=false` 和 `ledgerlab.stripe.api-key is required for Stripe reads`，仍存档，且不发送 HTTP。live 或非 test 前缀继续在启动时拒绝。失败结果不携带上游响应正文或原异常，防止上游回显凭据进入日志或异常信息。
- HTTP 与解析在 `NOT_SUPPORTED` 中执行（若调用方已有事务则暂挂）；结束后用 `REQUIRES_NEW` 短事务 INSERT 一行 `io.processor_fetch`，失败调用也如此。`fetched_at` 在 HTTP 尝试结束后取注入的 `Clock`，`recorded_at` 在存档事务内取同一时钟。调用方回滚不撤销存档。可选 `eventId` 必须引用已提交的 inbox 事件；无事件关联的主动查询传 `null`。数据库不可用或存档约束失败时抛出不带原始数据的 `Stripe fetch archive failed`，不能伪装成有存档的结果。
- 单对象的 `response_body` 是原始响应文本（未收到响应时为空串）。列表查询统一存为 **JSON 字符串数组**，每个字符串是按接收顺序保存的一页完整原文，包括非 2xx 或非法 JSON 的原文；这样不会重排对象字段、丢掉空白或无法保留坏 JSON。没收到任何完整响应时为 `[]`。SHA-256 对最终保存文本的 UTF-8 字节计算，与 V6 CHECK 一致；这是原始证据哈希，不是后续业务版本的规范化指纹。`request` 保存 GET 路径、筛选参数、purpose、协议版本、每页尝试的参数及收到的 HTTP 状态、最终 outcome，不保存 Authorization。
- 列表使用 `limit=100`，以每页最后一项的 `id` 作 `starting_after`，直到布尔值 `has_more=false`；空页却声称还有下一页、游标重复、非 2xx、超时、非法 JSON／字段，全部为 `complete=false`。失败时解析列表为空，只能通过存档查看半截证据。不在客户端去重、跳过 `type=payout` 或拒绝未知业务类型，保留重复行、带符号金额及可空的 balance transaction `source`，由后续核对识别差异。
- payout 返回 id、amount、currency、automatic、method、status、reconciliationStatus、arrivalDate、created；balance transaction 返回 id、type、source、amount、fee、net、currency、created。金额必须是可表示为 `long` 的 JSON 整数，拒绝小数、字符串、空值和溢出；时间从整数 epoch seconds 转为 `Instant`。创建区间是 `[from, to)`，对应 `created[gte]` 与 `created[lt]`；边界须为整秒且递增，避免截断造成范围变化。无效区间或 payout 编号也存档为失败，不发 HTTP。
- T04 测试以 JDK `HttpServer` 和 Testcontainers PostgreSQL 验证完整／中断分页、原文及哈希、整数解析、请求头、配置与密钥失败、事件关联、事务外 HTTP 和存档独立提交，支撑 AC-5.10、AC-5.17；不代表批次核对／快照导入已完成。只追加权限复用 T02 的 `SettlementTablesTest.ac_1_13_appendOnlyEvidenceRejectsAppUpdatesAndOwnerRewrites`，密钥启动拒绝复用 `StripePropertiesTest`（AC-8.2）。inbox worker 接线仍留给 T05。

### P2 T06 实现备注：异常证据与版本

- `payout.failed`／`payout.canceled` 已接入 inbox，不拉 balance transactions；按批次状态记录失败、冲突或历史，具体状态和 inbox 结果见 [state-machines 第 4 节 T06 备注](state-machines.md#p2-t06-实现备注核对失败重核版本与打款失败)。批次尚不存在时停放，建批次时原子唤醒。
- 已确认及后续状态的 `payout.reconciliation_completed` 再次完整拉取，比较按行身份排序的规范化业务内容与 payout 金额；新 fetch 作为冲突证据保存，原批次 `lines_fetch_id` 和已确认行不覆盖。分页中断只存档并重试，不做版本判断；JSON 空白、行顺序和币种大小写不制造冲突。重复 `payout.created` 仍不再拉取。
- `HELD(UNKNOWN_PAYMENT)` 的重核在请款事务里读取固定的 `lines_fetch_id`：校验存档为 `complete=true`、`purpose=BATCH_LINES` 且 query 的 payout 匹配，按 T04 的 JSON 字符串数组格式逐页解析。即使后来 HTTP 响应变化也只用这次完整证据；该事务不发外部请求。其他 HELD 原因不自动重核。
- 实际手续费与合同手续费不同时，批次仍按实际 `fee` 记账；手续费大于行毛额才 HELD 并开 `AMOUNT_MISMATCH`。P2 的 refund、adjustment、dispute 等行以 `UNSUPPORTED_LINE` 隔离，其原文留在 fetch，不能写入已核对批次行。

## 4. 编号与时间

| 我们的字段 | Stripe 来源 |
|---|---|
| `event_id` | `evt_…` |
| `processor_payment_ref` | `ch_…`（另存 `pi_…`） |
| `processor_refund_ref` | `re_…` |
| `batch_id` | `po_…`（所以我们的商户提现用 `mpo_` 前缀） |
| `balance_txn_id` | `txn_…` |
| `occurred_at` | 按事实取：请款 = Charge 的 `created`；退款执行或失败 = 带来该状态的事件的 `created`（查询或调用响应得到的，用我们收到的时刻并标注来源），**不是** Refund 对象的 `created`（那是创建时刻，pending 的退款可能几天后才成功）；批次 = payout 的 `created`；批次行 = balance transaction 的 `created` |
| `signed_at` | `Stripe-Signature` 里的 `t` |
| 批次 `arrival_date` | payout `arrival_date`（处理方预计到账日，不是银行记账日） |
| 手续费 | 支付对应 balance transaction 的 `fee`；合同预期值另算 |

P1b-5 请款服务的时间约定：`core.payment.received_at` 和 `recorded_at` 使用服务执行时注入的 `Clock`，同次插入取同一时刻；原始 webhook 的接收时刻仍保留在 `io.inbox_event.received_at`，通过 `source_event_id` 关联。`occurred_at` 使用证据中的 Charge `created`，不使用处理时刻。

## 5. 密钥与安全

- 只用测试模式密钥：优先用**受限密钥**（`rk_test_…`），只开退款写、charges/refunds/payouts/balance transactions 读。应用启动时检查前缀，看到 `sk_live_` / `rk_live_` 直接拒绝启动。
- `STRIPE_API_KEY`、`STRIPE_WEBHOOK_SECRET` 只从环境变量读：本地放 `.env`（已在 `.gitignore`），部署放 AWS Secrets Manager。仓库里只有 `.env.example`（值为空）。gitleaks 在 pre-commit 和 CI 里拦 `sk_`、`rk_`、`whsec_` 前缀。
- 每个环境一个 webhook 端点、一个签名密钥；本地开发用 `stripe listen --forward-to`，它给的临时签名密钥也只进 `.env`。
- 契约测试用的录制夹具（第 6 节）在提交前脱敏：只保留测试对象编号和金额，去掉账号编号、邮箱等。
- Stripe 账号由用户本人注册，必须是加拿大账号（结算币种 CAD）。

## 6. 契约测试：fake Stripe 和真实 Stripe 不能漂移

### P3 T06：fake 退款子集与差异

- `POST /v1/refunds` 接受 form 编码的 `charge`、正整数分 `amount`、可选 `metadata[ledgerlab_refund_id]`，强制测试 Bearer 密钥、固定 `Stripe-Version: 2026-09-30.endive` 和 `Idempotency-Key`。退款 GET 同样验证密钥与版本；P2 既有只读接口的版本行为未改。字段与错误外层形状对照 [创建退款](https://docs.stripe.com/api/refunds/create)、[退款分页](https://docs.stripe.com/api/refunds/list)、[幂等请求](https://docs.stripe.com/api/idempotent_requests)。本任务不连接真实 Stripe。
- 支持 captured/succeeded、非 live、CAD charge；未失败的 pending/succeeded 占用原 charge 的可退额度。余额只汇总未分配且 `available_on ≤ 模拟时间` 的 CAD net；执行新增立即可用的 `type=refund, amount=net=−A, fee=0, source=re_…` 行，payout 原有归集直接包含这行。不能再额外扣一次退款。资金不足 pending，不建余额行；charge 创建提交后、模拟时钟推进后、`POST /sim/refunds/process` 均按创建顺序扫描，执行有足够余额的项。真实 Stripe 的 pending 执行时间不可控，此扫描次序不是对真实调度的保证，也不是主应用的商户 FIFO 前缀规则。
- `GET /v1/refunds/{id}` 返回当前对象；列表支持 charge、limit（默认 10，1～100）、starting_after，按 created/创建序号倒序，返回真正的 has_more；同一模拟秒的先后由创建序号固定。metadata 不唯一，跨幂等键或键清理后的重复业务操作确实能产生第二笔退款。
- 创建事件使用当时对象快照；正常直接成功只有 `refund.created(status=succeeded)`；pending 执行后发 `refund.updated`。`fail_next` 先保存 pending 创建快照，再变 failed，发 `refund.updated` 和 `refund.failed`，不先扣款再返还。手动 fail 仅允许 pending；终态重复操作幂等、冲突操作 409，成功后再失败的撤销流程不在此子集。事件签名、redeliver/reissue、relay 均复用 P1/P2 路径。
- 故障计划通过 `POST /sim/refunds/faults` 替换全局或 charge 范围的计划。支持 timeout_before_create、timeout_after_create 计数，fail_next 原因，force_pending、suppress_events、event_before_response 布尔值，以及 list_page_timeout 页号。charge 明确指定的字段覆盖全局，其余继承；空对象清除对应范围。fault plan 和剩余计数持久化；更新原控制请求另存入 fault_plan。
- 创建前超时和指定列表页超时返回 504；创建后超时在已提交、已尝试投递后挂起响应，让客户端真正超时。Servlet 30 秒超时只用于回收挂起连接（最终 504），不推进业务时间、不靠 sleep 注入。沿用模拟器默认的同步 webhook 投递，因此 event_before_response=true 保证 relay HTTP 尝试完成才返回；若 relay 配置 DROP/DELAY/HOLD，它的完成只表示 relay 已接收，故障组合不保证主应用此时已收到事件。suppress_events 不创建或投递任何退款事件。
- `POST /sim/idempotency/purge {"before":"ISO-8601"}`（默认模拟 now）只标记 created 早于 before 且已满 24 小时的键；不自动定时清理。同键同参数重放仍返回第一次响应原文对应对象，故对象后来 succeeded 时首次 pending 响应也不改变。参数校验失败不缓存。模拟器串行处理同键竞争，不复刻 Stripe 所有并发错误与 500 缓存行为。
- 已知子集限制：不支持 PaymentIntent 发起、amount 省略、其他 metadata 键、其他币种/支付方式、完整 Refund 扩展字段、Stripe 自动重试与真实退款失败撤销。以上故障与可控清理是 fake 专用接口；P6 仍须用真实录制夹具核对所用字段，不能把这些测试当成真实沙箱验收。

### 真实夹具验证（P6）

- 在测试模式下跑一组固定场景，把收到的 webhook 和 API 响应录下来（脱敏后进仓库）。
- CI 里用这些录制夹具驱动适配器，检查映射结果（不需要密钥、不联网）。
- 同一组场景在 fake Stripe 上也跑一遍，比较两边产生的事件序列和对象字段（只比我们用到的字段）。有差异就以真实 Stripe 为准修模拟器。
- 真实沙箱运行（P6）产出一份运行报告：多少笔支付、退款、payout，chaos relay 注入了多少重复/乱序/丢弃，最终不变量是否全部成立。简历和 README 里的数字只能来自这份报告。

## 7. 开户后要先实测的假设

这些是按文档做的推断，接入前要在测试模式里逐条验证，结果回写本文：

1. 测试模式下自动 payout 会按日程生成，且 `balance_transactions?payout=` 对它可用（文档说这个过滤只对自动 payout 有效）。
   **实测 2026-11-02（T01）**：两笔支付后 CAD 可用余额 1158 分、待结算余额 9680 分；`GET /v1/payouts?limit=10` 返回空列表且 `has_more=false`。自动 payout 与按 payout 过滤明细待 T07 观察。
   **实测 2026-11-06 UTC（T07）**：自动 payout 会生成且按 payout 过滤可用。`po_…`（automatic、standard、CAD，金额 306651）创建于 11-06 00:47:29Z，含 27 笔支付，最早的 charge 在 11-02、最晚的 11-03 01:11Z，即最后一笔 charge 之后约 71.6 小时才出现首个 payout（新账号的初始等待期加上待结算时间；之后的节奏未观察）。`balance_transactions?payout=` 完整翻页后除 `type=payout` 行外 27 行，与本地批次行逐行（txn、类型、金额、手续费、净额）相等。
2. 测试卡 `4000 0000 0000 0077` 能让资金直接进入可用余额，便于快速产生 payout。它是美国卡，不能用来验证加拿大国内卡费率；费率要用加拿大测试卡另测。
   **实测 2026-11-02（T01）**：审查确认改用 9.3 的 automatic payment methods 参数后，CAD 1234 分的 `pm_card_bypassPending` 成功，charge 类型断言为 card、卡国家 US；fee=76、net=1158、status=available、available_on=1793645009（与 charge.created 相同），直接进入可用余额。国内公式预期 66 分，实际多 10 分；不据国际卡修改国内公式。旧参数被拒；旧幂等键绑定旧参数，再用新参数返回 `HTTP_400 type=idempotency_error`，因此为修订请求固定分配编号 4，原编号 1 退役且本地记录保留。
   **实测 2026-11-06 UTC（T08，逐笔）**：对账的 27 笔 `FEE_MISMATCH` 逐笔取 Stripe 的 charge 与 balance transaction（只读 GET）：27 笔全部是美国卡（visa），金额 690～19446 分，Stripe 手续费都等于 `30 + round(3.7%)`（整数四舍五入），27 笔零例外；本地期望是 `30 + round(2.9%)`。即美国卡比国内多 0.8 个百分点的比例费，固定 30 分不变，差额是 6～156 分、随金额增长。这是同一个 payout 内的实测；只观察到美国 visa 一种非加拿大卡，其他国家或品牌的费率没有数据，不推广。逐笔表在运行报告“手续费差异逐笔说明”一节。本地手续费模型和对账逻辑未改（是否加卡国家维度是业务决定，见 handoff 的“后续”）。
3. 加拿大国内测试卡的手续费等于 `30 + round(2.9%)`；舍入方式不同就改合同公式，不加容差。
   **实测 2026-11-02（T01）**：官方[国际测试卡表](https://docs.stripe.com/testing#international-cards)的加拿大 Visa `pm_card_ca` 成功；charge 类型 card、国家 CA。10000 分的实际 fee=320，与 `30 + round(2.9%)` 相等，net=9680、status=pending、available_on=1794182400。此单验证通过，不改变合同公式或加容差，不把单个金额推广为完整舍入验证。
4. 余额不足时退款进入 `pending` 的行为在测试模式下可复现（官方文档说卡退款只用可用余额、不够时 pending；fake Stripe 已按此建模）。
   **实测 2026-11-02（T01，延期）**：本任务没有创建退款，避免未来批次出现账本未知退款而 HELD；待 T07 经主应用发起退款时观察。
   **实测 2026-11-06 UTC（T07）**：经主应用对两笔已结算支付各退 100 分，Stripe 返回的退款状态都是 `succeeded`，**没有出现 `pending`**（此时账户可用余额充足，没有构造余额不足）。所以本条在测试模式下尚未复现，fake Stripe 的 pending 仍只靠官方文档建模，S3 场景不录。
5. 有能让 payout 失败的加拿大测试银行账户号。
   **实测 2026-11-02（T01，仅文档核查）**：官方 [Connect 测试文档加拿大 payout 表](https://docs.stripe.com/connect/testing#payouts)列出路由 `11000-000`、测试账号 `000111111113` 对应 `account_closed`，`000111111116` 对应 `no_account`。仅记录文档能力，未配置银行账户、未验证本账号失败 payout。
6. webhook 端点返回非 2xx 时的重试节奏（只用于报告，不写进业务逻辑）。文档说 live 模式最多重试 3 天，sandbox 只在几小时内重试几次，不能依赖测试模式的自动补投。
   **实测 2026-11-02（T01，仅文档核查）**：官方 [Automatic retries](https://docs.stripe.com/webhooks#automatic-retries)明确 sandbox 在几小时内重试三次，live 指数退避最长三天。本任务未注入失败、未测实际间隔；不能把 CLI 转发当作已验证的端点重试。
7. 测试模式的接口速率上限，决定流量生成器和演示页的限流值。
   **实测 2026-11-02（T01，仅文档核查）**：官方 [Rate limits](https://docs.stripe.com/rate-limits)列出 sandbox 全局 25 请求/秒、普通单端点默认 25 请求/秒，另有端点和并发限制。没有压测；这是文档上限，不是流量生成器应持续达到的目标。
8. 测试模式的自动 payout 会走到 `reconciliation_status=completed` 并发出 `payout.reconciliation_completed`。这一条决定 P2 的批次触发方式：P2 先按官方文档在 fake Stripe 里实现；如果那时测试账号已经注册，就在 P2 开工前先验证这一条，否则 P6 接账号后第一个验证。
   **实测 2026-11-02（T01）**：没有历史 payout；支付后可用 1158 分、待结算 9680 分。`reconciliation_completed` 待 T07 观察，未改变当前触发设计。
   **实测 2026-11-06 UTC（T07）**：会走到 `completed`。同一 payout 的 `payout.created`（00:47:29Z 创建）与 `payout.reconciliation_completed` 几乎同时（app 收到时刻相差不到 1 秒，00:47:30Z），之后约 5 分钟收到 `payout.paid`（00:52:30Z）。批次因此在 `payout.created` 当次就走到 `CONFIRMED`（补取明细成功），未依赖兜底轮询；从最后一笔 charge 到 `completed` 约 71.6 小时（与第 1 条相同的初始等待）。`reconciliation_completed` 事件的结果是 DUPLICATE_FACT，因为 created 时明细已就绪。
9. 用 Stripe 的 default test-mode sandbox 还是新建的独立 sandbox：接账号时选定一种并写在这里，文档里统一称“测试模式”。
   **已定（2026-10-23）**：用户注册了加拿大账号，使用 Stripe 自带的默认测试沙盒（Dashboard 显示“测试企业 沙盒”），不另建 sandbox。开发用受限密钥 `ledgerlab-dev`（`rk_test_…`，只放在本地 `.env` 的 `STRIPE_API_KEY`），权限：Balance、Balance Transaction Sources、Events、Payouts 读取；Charges and Refunds、Payment Intents 写入，其余全部无。第 1～8 条尚未实测。第 8 条原定 P2 开工前实测；2026-10-23 改为 P2 期间做（新账号的首次自动 payout 要等 Stripe 的初始等待期，开工前等不到），P2 先按官方文档在 fake Stripe 里实现 `reconciliation_completed` 触发，并保留“查询 payout 对象看到 `completed`”的后备路径，所以实测结果即使相反也只改触发方式、不改批次核对。
10. 受限密钥能否用于 `stripe listen`（`--api-key rk_test_…`）；不能则用户本人 `stripe login` 一次。
   **实测 2026-11-02（T01）**：Stripe CLI 1.55.6 使用当前受限密钥调用 `listen --print-secret` 不能取得签名密钥，错误类别 `PERMISSION_ERROR`，需用户登录。按审查结论，`env.sh` 默认 `STRIPE_CLI_USE_LOGIN=1`；listen/resend 不带 `--api-key`，并从 CLI 子进程环境移除 `STRIPE_API_KEY`。本轮登录模式返回 `LOGIN_REQUIRED`，输出“需要用户 stripe login”后跳过 f 项，其他探测成功则整体退出 0。未自动登录、未存储签名密钥；REST 仍用受限测试密钥。
11. `stripe events resend evt_…` 能否把事件重投给正在运行的 `stripe listen`（9.2 补投依赖它）。
   **实测 2026-11-02（T01，延期）**：已找到 bypass 的 `charge.succeeded`（`evt_3UXQCaF5hIinUF490brSX7aS`）；CLI 未登录，未执行 resend 或 60 秒接收观察。待用户登录后由 T04 观察，不能断言支持或不支持。官方[手工重试说明](https://docs.stripe.com/webhooks#manual-retries)的 webhook endpoint 示例不能代替本地 listen 实测。
   **实测 2026-11-03 UTC（T04）**：登录模式 `events retrieve` 读到上述事件并核对 `livemode=false`；listen 就绪后 `events resend` 返回该事件 JSON（无 error），relay 录制表也出现新的接收行，证明本地 listen 能接收重投。CLI 错误同样可能退出 0，所以脚本解析 JSON 并独立核对 relay。最初监听子进程在 Windows 退出，显式为 Stripe CLI 设置 CREATE_NO_WINDOW 后保持运行；用 Stripe resend 恢复漏收的本 run 20 个 charge.succeeded，未新增支付或伪造入账签名。
12. 真实事件的 `api_version` 是否等于第 8 节固定的版本；不同就按第 8 节“升级 API 版本是一次单独的改动”处理，P6 先如实记录。
   **实测 2026-11-02（T01，部分）**：初始 `account.updated` 与支付后的最近五条事件（含 `charge.succeeded`、`charge.updated`、`payment_intent.created`、`payment_intent.succeeded`）均为 `api_version=2026-09-30.endive`。经 listen 投递的版本待用户登录后由 T04 观察；API 列表结果不替代 webhook 契约验证。
   **实测 2026-11-03 UTC（T04）**：20 笔真实支付事件经 listen → relay → inbox，录制原文与 inbox 的版本均为 `2026-09-30.endive`，20/20 CAPTURED；未升级协议版本。

第 9 条补充：**实测 2026-11-02（T01）**：从 `.env` 单独加载的测试密钥可以读取 balance、payouts、events、charge 及其 balance transaction，并创建确认两笔 PaymentIntent；balance、PaymentIntent、charge 明确 `livemode=false`。沿用已定的加拿大默认测试沙盒，不另建 sandbox；REST 权限不代表 CLI listen 获权。完整记录与后续观察项见 [p6-probe.md](../handoff/p6-probe.md)。

## 8. 协议版本（P1 开工前固定）

- Stripe API 版本固定为 **`2026-09-30.endive`**（2026-10-21 核对的当前版本；命名规则是发布日期加代号）。fake Stripe 的事件和对象形状、录制夹具、webhook 端点的 API 版本都用它。
- P1b 自行实现 HMAC-SHA256 验签，不引入 `stripe-java`；P3 需要出站调用时再选用与该 API 版本对应的 SDK 并固定版本。升级 API 版本是一次单独的改动，要重录夹具并跑契约测试。
- 事件格式用 snapshot 事件（带完整对象），不用 thin 事件。
- 验签：用原始请求体按 Stripe 方案算 HMAC；时间窗口**两侧**都检查（`|now − t| ≤ 300 秒`）。SDK 的验签函数只拒绝过旧的时间戳，未来时间要我们自己补检查。
- P1b 配置为 `ledgerlab.stripe.webhook-secret`（`STRIPE_WEBHOOK_SECRET`，必填且以 `whsec_` 开头）和 `ledgerlab.stripe.api-key`（`STRIPE_API_KEY`，可空，非空须以 `sk_test_` 或 `rk_test_` 开头）；启动时校验且错误不包含密钥值。签名有效但 JSON 不合法或缺少 `id/type/api_version/livemode/created` 时返回 400、不入库。
- P1 的 fake Stripe 只需要 `charge.succeeded`；事件 JSON 按上述版本的官方对象定义写，P6 用真实录制夹具对照（第 6 节）。

## 9. P6 真实沙箱运行（开工设计，2026-11-02）

本节是 P6 的设计正本，补齐第 1 节“真实沙箱运行”一列没讲清的部分。原则不变（ADR-0016）：真实沙箱是**运行模式**，不进每次 CI；CI 只跑录制夹具的契约测试。只用测试模式；`livemode=true`、`sk_live_`/`rk_live_` 一律拒绝。

### 9.1 运行形态

- **一套长期运行的本机栈**：Compose 项目名固定 `ledgerlab-sandbox`，profile `sandbox`，**持久卷**（自动 payout 要等一到几天，栈要能跨天、跨重启继续）。服务：postgres、simulator（只用 chaos relay、模拟银行和 9.3 的沙箱桥；fake Stripe 接口在但不用）、app。和冒烟用的一次性项目完全分开，端口也分开（app 18080、simulator 18081、postgres 15432，只绑 127.0.0.1）。
- **入站**：`stripe listen --forward-to http://127.0.0.1:18081/relay/stripe --events <第 2 节订阅列表＋charge.dispute.created>` → chaos relay → app `POST /webhooks/stripe`。relay 原样转发，签名是 Stripe 的真实签名。`stripe listen` 由脚本作为后台进程管理（pid 和日志在 `.sandbox/`），签名密钥用 `stripe listen --print-secret` 取得。
- **出站**：app 的 `STRIPE_API_BASE_URL` 指向 relay 的出站代理 `http://simulator:8081/relay/stripe-api`（9.4），代理再转发到 `https://api.stripe.com`。所有读（payout、balance transactions、退款查询、对账快照）和写（退款）都经过它，故障注入和录制都在这一处。
- **密钥**：`STRIPE_API_KEY` 只从本机 `.env` 读，脚本 `scripts/sandbox/env.sh` 只做 `export`，绝不 echo；前缀必须是 `rk_test_`（或 `sk_test_`），否则退出。`stripe listen` 给的 `whsec_` 写进 `.sandbox/secrets.env`（`.gitignore` 忽略整个 `.sandbox/`），app 启动时注入。日志、报告、夹具里不出现任何密钥（gitleaks 兜底）。Stripe CLI 用 `--api-key` 传同一把受限密钥；如果实测（第 7 节第 10 条）受限密钥不能 `listen`，需要用户本人执行一次 `stripe login`，这是 P6 唯一可能需要用户动手的地方。
- **运行编号**：每次运行有 `run_id`（`sbx_` + UTC 时间）和开始时刻，写进 `.sandbox/run.json`；流量生成器给 PaymentIntent 的 `metadata[ledgerlab_run_id]` 带上它，报告按它和时间窗统计。
- **模拟时钟跟随现实**：simulator 在 sandbox profile 下打开 `sim.clock.follow-wall=true`，每秒把模拟时钟推进到现实 UTC（只进不退），relay 的延迟/扣留和模拟银行的入账日因此按现实时间走。默认（测试、冒烟）仍然冻结，行为不变。

T04 实现备注（2026-11-03）：沙箱数据库不沿用 `.env` 密码。首次 `up` 用 Python `secrets` 为 postgres、migrator、app、readonly、simulator 分别生成密码，持久保存在 `.sandbox/secrets.env`；之后只补缺失项，已有值绝不轮换，更新 webhook 签名密钥也保留五项密码。文件原子替换，写入前收紧权限（POSIX 0600；Windows 仅当前用户 ACL）。普通 down 和 `--new-run` 均保留密码，只有确认 `down --purge` 且删卷成功后才清除五项；下次 up 重新生成。`.env` 只通过 env.sh 读取 Stripe 测试密钥，Compose 显式使用空 env-file。

### 9.2 漏收补投

T01 实现备注（2026-11-02）：第 7 节已证实当前受限密钥不能 listen，后续沙箱脚本默认使用 `STRIPE_CLI_USE_LOGIN=1`；listen/resend 不传 `--api-key`，并移除 CLI 子进程的 `STRIPE_API_KEY`，REST 调用仍使用 env.sh 加载的受限测试密钥。用户本人登录之前，T01 跳过 CLI 观察项；后续任务按各自退出码约定处理。

本机会休眠，`stripe listen` 会断，测试模式的自动重试也不可靠（第 7 节第 6 条）。所以每次运行和每次恢复都先做补投：

1. 用 Events API `GET /v1/events?created[gte]=<窗口起点>&types[]=…` 翻完全部分页（任何一页失败都算“不完整”，不据此下结论），得到订阅类型的事件编号。
2. 对照 app 已收到的事件（`GET /api/events/{eventId}`，404 = 没收到）。
3. 缺的用 `stripe events resend evt_…` 让 Stripe 重新签名投递（实测第 7 节第 11 条确定 CLI 监听会不会收到重投）。**不伪造签名，不绕过 relay 直接写 inbox。**
4. 重投后仍缺的事件列进运行报告“漏收”一栏。payout 的明细就绪本来就有兜底轮询（第 2 节 T05 备注），不依赖补投。

窗口起点默认取运行开始时刻，可用参数改早（例如覆盖 P6 实测时产生的对象）。

T04 实现备注（2026-11-02）：按任务补充，`up.sh` 新 run 的窗口默认提前到 `2026-10-20T00:00:00Z`，覆盖 T01 探测及账号早期测试支付；`--window-start` 可覆盖。已有 run 沿用原窗口，变更须 `--new-run`。

**T05 实现备注（2026-11-03 UTC）**：`catchup.sh [--window-start <Instant>]` 经 env.sh 加载 REST 测试密钥，通过出站 relay 查询 Events API；上界固定为本次开始时的整秒（`created[lt]`），订阅列表与 listener 一致，limit=100，以最后一个事件号继续翻页。全部页成功并验证 test mode、类型、窗口和游标推进后才对照 inbox；任何页失败、格式错误或游标循环都输出“不完整”、退出 2，不重投、不更新漏收清单，也不继续银行同步。按官方 [Events 列表契约](https://docs.stripe.com/api/events/list)，事件仅保留 30 天，窗口超出保留期同样不能声称完整。参数覆盖只作用于本次 catchup，不改 run.json。

只有 app 详情 404 才是漏收，已经收到但 PARKED／REJECTED 的事件不算漏收；其他查询失败终止，不当作 404。重投复用 T04 已实测可达 listener 的登录模式 `stripe events resend evt_…`，同时检查退出码、JSON 中的同一事件编号、`livemode=false` 和无 error。所有缺失事件各尝试一次后，每 2 秒轮询，最多等待 60 秒再对照；仍缺事件写 `.sandbox/missing-events.json`，含 run 和窗口，摘要写 `target/sandbox/catchup-<UTC时间>.json`。仍缺／重投失败退出 1；完整且补齐、银行成功才退出 0。没有本地重签名或直接写 inbox。

随后调用沙箱银行 sync；成功后读取模拟银行实际流水，按最早 visible_date 到模拟多伦多当天逐日取 CSV（无流水仍导当天），原字节 POST `/api/bank-statements`，要求 ACCEPTED。包括 visible_date 是为了覆盖先 PENDING 后 BOOKED 的文件。使用 simulator 观察者连接将 `/sim/clock` 返回的时刻按 PostgreSQL 的 America/Toronto 时区转成日期，避免 Windows Python 缺少 IANA 数据而新增依赖；不读 app 库或 sim_truth。已有 app 按 CSV SHA 去重，相同日报版本返回原 fileId；脚本不另设可能掩盖新版文件的“已导入日期”缓存。重复运行实测 fileId 不变。T05 首次补回 1 个真实漏收事件，后续补投数为 0；当时 paid payout 为 0，银行同步只证明空结果及空日报幂等，不代替 AC-8.8。

### 9.3 沙箱桥（simulator 的 `sandbox` 包，只在 sandbox profile 装配）

- **流量生成器** `POST /sim/sandbox/payments {"count":20,"card":"pm_card_bypassPending","seed":1}`：按种子生成 500～20000 分的金额，逐笔 `POST /v1/payment_intents`（`amount`、`currency=cad`、`payment_method=<测试 PaymentMethod>`、`automatic_payment_methods[enabled]=true`、`automatic_payment_methods[allow_redirects]=never`、`confirm=true`、`metadata[ledgerlab_run_id]`），`Idempotency-Key = <run_id>:pay:<card>:<seed>:<序号>`，所以重跑同一批不会多扣。只走自动请款，成功后断言 charge 的 `payment_method_details.type == "card"`。卡：`pm_card_bypassPending`（资金立即可用，便于尽快产生 payout）和加拿大国内卡（验证第 7 节第 3 条费率）。每笔结果追加进 simulator 自己的表（`sim_sandbox` schema，主应用不读）。T01 按 2026-11-02 审查结论采用此请求契约并实测成功；错误记录只允许 `error.type`、`error.code`、`error.param`、`error.decline_code` 等安全字段，不记录 `error.message` 或完整错误原文。
- **模拟银行对接真实 payout** `POST /sim/sandbox/bank/sync`：列出窗口内 `status=paid` 的 payout（翻完全部分页），对 `automatic=true`、`method=standard`、`currency=cad`、`amount>0` 的调用已有的 `IncomingTransfers.receive(po_…, amount)`（已有幂等，重复同步不重复入账）；入账日仍由模拟银行自己的日历和计划决定（第 1 节）。simulator 只读 Stripe，不读主应用的库。
- 两个接口都用同一把 `STRIPE_API_KEY`，启动时同样拒绝 live 前缀；请求也经过 9.4 的出站代理，以便录制。

**T03 实现备注（2026-11-02）**：沙箱桥仅在 `sandbox` profile 注册。必填 `sim.sandbox.stripe-api-key/run-id/window-start`，分别由 `STRIPE_API_KEY`、`SIM_SANDBOX_RUN_ID`、`SIM_SANDBOX_WINDOW_START` 注入；base URL 默认 `http://localhost:8081/relay/stripe-api`，可用 `SIM_SANDBOX_STRIPE_BASE_URL` 覆盖。run-id 限 `sbx_` 加 1～100 个字母、数字、下划线或连字符；窗口接受 ISO-8601 Instant。错误信息不回显配置值。API 版本固定 `2026-09-30.endive`，不跟随重定向、不自动重试；连接 5 秒、完整响应期限 60 秒。

金额序列用 `java.util.Random(seed)` 的 `500 + nextInt(19501)`，序号从 1 开始，每批 1～50 笔。白名单为 `pm_card_bypassPending`、T01 实测的 `pm_card_ca`。完整幂等键包含 card 和 seed（修正本节原简写），相同种子不同卡不会撞键；同卡同种子的扩容批次复用前面序号。请求显式 `capture_method=automatic`，展开 `latest_charge`；若返回字符串则 GET charge，再断言 test mode 和 card 类型。部分失败只追加安全结果，不回滚其他支付；完整原文不进沙箱桥表或返回值。

发送前追加 started、完成后追加结果，成功重放直接返回已有结果；未确认尝试在首次发送满 23 小时后拒绝再次 POST（沿用 T01 的幂等保留窗口保护）。`GET /sim/sandbox/payments` 返回当前 run 的全部尝试行，按 ordinal/id 排序；`GET /sim/sandbox/summary` 按逻辑身份 `(card, seed, ordinal)` 的最新结果统计各卡 count/amount、succeeded/failed/pending 和 payouts_received，started 单列 pending。金额合计包含失败尝试对应的逻辑支付，不把重试行累计成新支付。

银行同步先拉完全部分页，再在同一事务中调用银行并追加同步记录；任何页 HTTP/解析/游标失败返回 502、`pages` 为已读完整页数、零接收。记录包含 seen/received/skipped/pages/httpStatus/errorCode；成功的 received 为本次新通知的 payout 数，重复同步列入 ALREADY_RECEIVED。跨 run 去重由持久同步记录与银行自身幂等共同保证；同 run 的报告计收到的唯一 payout。额外拒绝 live、窗口外和非 paid 对象，金额沿用模拟银行既有正整数且小于 2^53 的界限（没有放宽银行范围）。银行接收发生错误时整个本次入账事务回滚并追加 502 记录。V8 表、权限见数据字典 T03 备注。

`sim.clock.follow-wall` 默认 false，sandbox 默认 true，显式配置可覆盖；每秒用注入的现实 Clock 原子推进 SimulationClock 到较晚时刻，执行到期回调、原有推进监听器，再推进银行计划。默认冻结路径和手动推进接口保持原行为。沙箱桥测试只用 JDK HttpServer 与真实 PostgreSQL，支持 AC-8.4、8.8、8.10，密钥防护支持 AC-8.2；不替代真实沙箱端到端验收。

### 9.4 chaos relay 的出站代理

- `ANY /relay/stripe-api/**` 原样转发到 `sim.relay.upstream-api-url`（默认 `https://api.stripe.com`；测试用 loopback）：方法、路径、查询串、请求体，以及 `Authorization`、`Stripe-Version`、`Idempotency-Key`、`Content-Type` 头；响应状态、正文和 `Content-Type`、`Request-Id` 原样返回。`Authorization` 带 live 前缀的直接 403，不转发。不跟随重定向。
- relay 计划增加出站规则：`{"direction":"OUTBOUND","method":"POST","path_prefix":"/v1/refunds","action":"DROP_RESPONSE","times":1}`。动作：`PASS`；`DROP_RESPONSE`（请求照发、等上游响应完成，然后给调用方 504，制造“对方已执行、我们不知道”）；`FAIL_BEFORE_SEND`（不转发，直接 503）。`times` 用完后回到 PASS。原有入站规则不带 `direction`，含义不变。
- 出站日志进新表（只追加）：方法、路径（不含查询串）、查询参数名列表（不含参数值）、上游状态、动作、耗时、请求与响应正文的 SHA-256；**正文只在录制开关打开时保存**（9.5），头一律不存。
- `GET /sim/relay/stats?since=…` 返回两个方向各动作的次数，供运行报告用。

**T02 实现备注（2026-11-02）**：出站代理与 stats 由 `sim.relay.outbound-enabled=true` 或 sandbox profile 启用；默认模式保持原行为。上游只能 HTTPS 或 loopback HTTP，禁止 userinfo/query/fragment；连接超时默认 PT5S，完整响应读取期限默认 PT60S（包含正文停滞），分别通过 `sim.relay.connect-timeout/read-timeout` 配置。无重定向、无自动重试。live 凭证的 403 不记录交换、不消耗规则次数；网络错误/超时记选中的动作，上游状态及响应哈希为空，返回 504。

出站规则按方向、method、原始路径前缀匹配，跳过耗尽的规则，无可用规则 PASS；次数在匹配时同步消耗，换计划重置，GET 展示原配置次数。stats 的 since 必填且含边界；入站统计接收行而非转发副本，HOLD_TIMEOUT_RELEASE 另计；出站每个交换计一次，零次动作也返回。V7 表与授权见数据字典 T02 实现备注：正文保存 bytea，默认关闭，sandbox 默认打开；关闭时不新增 inbound_recording。头从不保存。入站原日志仍用模拟时间，出站与录制用注入的审计 Clock。主应用两个适配器的 loopback 回归测试确认共用 transport 保留 base URL 路径前缀，无需生产代码修复。

代理路径单独跳过 Spring 的 form 参数解析过滤器，避免 PUT/PATCH/DELETE 的 form 正文在转发前被消费；其他模拟器接口保留原表单处理。

### 9.5 录制与脱敏

- 录制开关在 sandbox profile 下默认打开：入站 webhook 原文（relay 收到的请求体）和出站交换的请求/响应正文存进 simulator 的录制表。
- `scripts/sandbox/record-fixtures` 按场景导出夹具到 `src/test/resources/contracts/stripe-sandbox/<场景>/`，带 `manifest.json`（场景、顺序、类型、API 版本、录制时间、run_id）。导出时脱敏（simulator 里的 Java 代码，有单测）：账号号 `acct_…` → `acct_REDACTED`；邮箱、姓名、地址、电话、`billing_details`、`receipt_url`、`client_secret`、卡 `fingerprint` 置空或去掉；对象编号（`ch_`/`pi_`/`re_`/`po_`/`txn_`/`evt_`）、金额、币种、时间、状态保留。导出后再扫一遍：出现 `sk_`、`rk_`、`whsec_`、`_secret_`、`@` 即失败。
- 场景固定为：S1 支付成功（charge.succeeded＋其 balance transaction）；S2 退款成功（POST /v1/refunds 响应＋refund.created/updated）；S3 退款 pending（余额不足时，能复现才录）；S4 payout（payout.created → reconciliation_completed → paid，及 `balance_transactions?payout=` 分页）；S5 签名错误（本地篡改，不需要录制）。

**T06 实现备注（2026-11-02）**：新增仅 sandbox profile 注册的 `GET /sim/sandbox/recordings`。kind 必填 webhook/api；event_type 精确匹配入站类型，path_prefix 按字面前缀匹配 API 路径。按录制表 id 升序返回数组，limit 默认 100、范围 1～1000；附加 after_id 游标可完整翻页。每条含 id、kind、recorded_at、脱敏后的 body；webhook 另含 event_type，API 另含 method、path、status。API 只导出响应正文，不导出请求正文、查询值或头；无响应正文的交换不返回。

`RecordingRedactor` 是无 I/O 的纯 Java 变换：递归删除隐私字段，字符串内嵌账号替换为账号占位值，含邮箱标记的字符串置 null；原录制字节保持不变。未参与适配器解析的 risk_level/risk_score/network_status 等字段名也会命中严格子串扫描，故一并删除含禁用标记的字段。未知位置的密钥标记不自动放行，脱敏结果连同接口元数据再扫一次；加入非占位账号检查。无法安全解析、含尾随 JSON 或残留标记时返回 422，错误不回显正文。

`sh scripts/sandbox/record-fixtures.sh --scenario s1` 核对本地 run 与 simulator summary 一致，以成功支付尝试的 charge 编号及 webhook metadata.ledgerlab_run_id 双重关联当前 run，选最早录制的一条 charge.succeeded。余额响应按 source=该 charge 关联，仅取成功 GET 的完整列表响应，保留整个响应。没有对应响应时 manifest 显式写 not_available；提交的 T06 S1 已包含响应（经 relay 做一次 source 过滤的只读查询后再导出），没有新增支付或退款。输出为 s1-payment 下两条录制和 manifest，后者含顺序、类型、固定协议版本、录制时间、run_id、全部期望映射值及准入结果；API 版本沿用沙箱所有请求固定的版本，录制表不保存头。两个资源目录都在写前用 Python 扫描器检查全部内容，重跑覆盖同场景并清除过时 JSON。离线自检为 `python scripts/sandbox/selftest.py`。

`sh scripts/sandbox/check-payout.sh` 从当前 run.window_start 起完整翻页查询 Stripe，全部页成功后才判断 automatic=true、paid、reconciliation_status=completed；有符合对象输出编号并返回 0，没有或仍在处理输出状态并返回 75，调用或分页失败返回 2。T06 实跑窗口内暂无 payout，返回 75；此结果不表示 AC-8.8 已验收。

**T08 实现备注（2026-11-06，AC-8.3）**：`record-fixtures.sh --scenario s1|s2|s3|s4|all`。S2 取本 run 内第一笔 `POST /v1/refunds` 成功响应、同一退款的 `refund.created`／`refund.updated` 两个事件、按 charge 的完整列表响应（`has_more=false`），以及一条单个退款的 GET（有则录）；S4 取一个 payout 的 created → reconciliation_completed → paid 三个事件、`GET /v1/payouts/{id}`，和该 payout 的 `balance_transactions?payout=` 响应（含 payout 自己的一行）。S3 只在录到 `status=pending` 的退款时导出；测试模式没出现过，脚本输出 `NOT_REPRODUCED`、不写任何文件，所以 S3 没有夹具目录。S4 的 28 行在一页内返回（limit=100），夹具没有多页；分页由适配器既有的分页单测覆盖。夹具 JSON 一律以 LF 写出（`write_json`），两份副本逐字节相同。退款和 payout 事件里的 `request.idempotency_key`（就是本地退款号）导出时换成占位值：没有解析器读它，而 gitleaks 的通用规则分不清它和密钥；`metadata.ledgerlab_refund_id` 保留。

`StripeSandboxContractTest` 对 webhook 按事件类型分支重放：退款事件交给 `RefundParser`，payout 事件交给 `StripeReadParser.payout`；对 API 响应按对象类型分支：退款创建／单个查询／按 charge 列表走 `StripeRefundAdapter`（核对请求的路径、幂等键、表单、`Stripe-Version`），payout 查询和明细页走 `RestStripeReader`。遇到没有分支的事件或对象类型直接失败，不静默跳过。`FakeStripeContractTest` 增加 S2、S4：fake 跑退款和 payout 生命周期，比较事件序列和所用字段；联合类型字段（含 `missing`）只要求两边都落在声明的类型内，其余仍要求类型一致。差异见 9.9，测试里显式断言了这些差异，哪天 fake 改了会提醒更新本表。

### 9.6 契约测试（AC-8.3，进 CI，不联网）

- 主应用：录制的 webhook 喂给 `StripeEventParser`、请款准入、退款解析、payout 解析；录制的 API 响应由 loopback HttpServer 回放给 `RestStripeReader`、`StripeRefundAdapter`。断言映射出的字段等于 manifest 里写的期望。
- “我们用到的字段”只在一个地方定义：`contracts/stripe-sandbox/used-fields.json`（按对象类型列字段路径）。simulator 侧测试让 fake Stripe 跑同样的场景，比较事件类型序列和这些字段的存在性与类型；夹具在 simulator 测试资源里放一份逐字节相同的副本（沿用 P3 `refund-updated-failed.json` 的做法，主应用侧有测试检查两份哈希一致）。差异以真实 Stripe 为准修 fake；修不了的（例如真实的 pending 调度）写进本节“已知差异”表和运行报告。

**T06 实现备注（2026-11-02）**：`StripeSandboxContractTest` 遍历场景 manifest，将事件信封、Charge 全部映射值与 manifest 比较，再在真实 PostgreSQL 的 app 事务中调用 `PaymentCapture` 验证准入。API 响应在 JDK loopback HttpServer 回放给 `RestStripeReader`，逐字段比较全部余额行；测试不访问 Stripe。S1 尚未有自动 payout，回放时用合成 payout 查询参数调用同一 reader，验证的是响应映射，不把它当作真实 payout 筛选验收。

字段目录使用 JSON Pointer 与 JSON 类型，整数须能表示为 long；`|` 表示解析器接受的联合类型，`missing` 只用于代码显式允许缺省的退款字段。目录逐项核对 `StripeEventParser`、`ChargeParser`、`RefundParser`、`StripeRefundAdapter`、`StripeReadParser`；payout webhook 复用后者。S1 实际校验 event、charge、balance_transaction，退款与 payout 夹具由 T08 补充，新增场景需显式增加回放分支，不能静默跳过。列表外层的 data 数组和 has_more 布尔值也校验。

`FakeStripeContractTest` 在真实 PostgreSQL 上生成 S1 charge，比较事件类型序列、版本与所用字段类型，并核对请款事实。序列断言后才创建一个 fake payout，以便经既有公开接口读取该 charge 的余额行（fake 列表要求 payout 过滤）；这一步不计入 S1 事件序列。手续费保留第 9.9 节国际卡差异，测试显式说明并断言 fake 的国内合同公式。主应用另检查两份完整目录的文件集合与 SHA-256 相同，并再次扫描全部 JSON。T06 没有修改主应用生产代码、fake 业务行为或数据库结构。

### 9.7 对账接真实数据（AC-8.9）与运行报告（AC-8.10）

- 对账沿用 P2 的 `RECON_SNAPSHOT` 路径，只是 base URL 指向出站代理；输入存档、哈希和截止时点照旧。差异里的 subject 必须是 Stripe 对象编号（`po_`、`txn_`、`ch_`、`re_`），报告逐条列出。
- `scripts/sandbox/report` 只用公开 HTTP 接口（app 的 `/api/*`、simulator 的 `/sim/*`）和 Stripe 只读列表生成 `docs/runs/sandbox-<run_id>.md` 与同名 `.json`（脱敏后提交）：支付/退款/payout 数量（Stripe 侧和本地两边）、各状态分布、relay 两个方向各动作次数、`stripe events resend` 次数、漏收事件、不变量检查结果（违反次数，必须为 0 才算通过）、对账发现、fake 与真实 Stripe 的已知差异。不变量用账本已有的审计检查；没有 HTTP 入口就在 `investigation` 加只读的 `GET /api/audit/invariants`。
- 简历和 README 里的数字只能引用这些报告。

### 9.8 沙箱验收脚本

`scripts/sandbox/` 下（Git Bash／POSIX sh，Python 3 做 HTTP 断言，和冒烟脚本同一风格）：`up`、`down`（保留卷）、`status`、`catchup`（9.2＋银行同步＋银行流水导入）、`traffic`、`accept`（AC-8.4、8.5、8.6、8.9 的顺序断言）、`check-payout`（AC-8.8，payout 走完才通过，没走完输出“等待中”并以 75 退出）、`record-fixtures`、`report`。这些脚本需要密钥，不进 CI。

**T04 实现备注（2026-11-02）**：已提供 `up.sh/down.sh/status.sh/traffic.sh/accept.sh`，shell 入口统一加载 env.sh，Python 从本地 secrets.env 读取五个数据库密码并只注入 Compose 子进程；Compose 使用空 env-file，不求值 `.env`，固定项目 `ledgerlab-sandbox` 和 sandbox profile。叠加文件以 `!override` 替换端口/profile/数据库卷（需 Compose 2.24.4+），只开放 127.0.0.1 的 18080/18081/15432，独立持久卷 `sandbox-pgdata`。fake Stripe 不配置 API key，主应用和沙箱桥均经出站 relay；银行出站仅在此叠加配置开启。

`up` 每次解析 CLI `events retrieve evt_3UXQCaF5hIinUF490brSX7aS` 的 JSON 核对登录账号，resource_missing 或无法证明 test mode 时退出 78 并提示用户重新登录，不把 CLI 退出码 0 当成功。签名密钥只写 `.sandbox/secrets.env`（POSIX 0600，Windows 尽量移除继承 ACL 并仅授予当前用户）；日志在落盘前脱敏。后台 Python supervisor 管理 listen，Windows 下 supervisor 与 CLI 子进程均显式无窗口启动，保存 PID、ready 和心跳，通过随机 token 请求退出，不凭一个可能复用的 PID 杀进程。重复 up 沿用 run，已就绪的 listen 不重复启动；`--new-run` 归档旧编号。down 保留卷，`--purge` 必须交互输入当前 run_id，删除后撤销当前 run，避免同编号重建触发旧幂等键。

`traffic` 使用 T03 幂等身份，当前 run 最多 50 个逻辑支付；`accept --only ac-8.4` 从 simulator 的当前 run 支付尝试恢复最新逻辑结果，不生成支付，至少 20 笔成功且全部唯一。（T08 补记：支付被真实 payout 结算后状态是 `SETTLED`，判据因此改为 `CAPTURED`，或带 `PAYMENT_CAPTURED` 分录的 `SETTLED`；结算不能代替请款分录。）最多轮询五分钟，逐笔验证 CAPTURED、对应真实 charge.succeeded 已进 inbox 并 PROCESSED、版本为固定协议。录制原文由脚本以 simulator 角色只读 `sim_chaos.inbound_recording`（沿用 smoke/p3 的观察者 SQL 模式），校验 SHA-256，原文只留进程内存；主应用仍不读模拟器数据库，脚本不读 sim_truth。

**T05 AC-8.5 实现备注**：先保存当前 run 的各支付状态及零违反基线，从 seed=2 六笔之外选 3 个已 CAPTURED 的真实 charge.succeeded，每个 resend 两次，并等待新增两条投递。`traffic` 的 card 参数默认 pm_card_bypassPending，显式传卡的旧用法不变。

为让乱序不依赖切换计划的竞态，同一 seed=2 批次先 count=1，再 count=6（后一次复用第 1 笔，所以只新建 6 个 PaymentIntent）。第 1 笔先按类型 HOLD_AND_REORDER(after_events=2)，看到 relay 接收记录后改为精确事件号 HOLD，其余 charge.succeeded DUPLICATE(copies=3)；随后生成其余 5 笔。重跑时沿用保存的六笔身份，由 Stripe resend 已有事件，不换 seed、不再新建支付。结束或失败均尝试清空本脚本设置的计划；启动时已有非空计划则失败，避免覆盖其他场景。

验收要求 stats 增量为 HOLD=1、DUPLICATE=5、HOLD_TIMEOUT_RELEASE=0；另用 simulator 角色只读 relay_log 验证每个副本均 200，扣留 receipt 在两个不同的后续事件完成全部三次转发后才释放。最多等 60 秒获取录制／重复投递、120 秒等待支付处理；超时不会当作通过。9 个事件须全部 PROCESSED/APPLIED、各仅一条 NEW，其余投递为 DUPLICATE；原 3 个事件的业务结果、支付号、请款分录号及金额保持不变。支付详情 postings 按 entryId 分组核对一条 PAYMENT_CAPTURED（两条借贷 posting），inbox.subjectId 按现有契约关联 ch_ 而非本地 pay_；最后审计零违反。结果与清空计划状态落 `accept-<UTC时间>.json`，离线自检入口为 `python scripts/sandbox/selftest.py`。

篡改用例由脚本用本地签名密钥和当前时间戳生成 HMAC，再改正文一个字节、去掉签名头、或用早 400 秒时间戳，三次直接发 app 均须 401；这些是脚本签名而非 Stripe 原签名，不发送“正确正文＋正确签名”。真实验签证据是此前至少 20 笔经 listen → relay → inbox 的真实事件。结果（包括失败）脱敏写 `target/sandbox/accept-<UTC时间>.json`，列出 charge/event、录制哈希、状态、协议版本、负例结果和不变量。

（P7 T01 起该接口在所有配置下可用，覆盖 INV-1～INV-12，返回 `{checkedAt, checks, violations}`，见 money-model 第 5 节的实现备注；下面是 P6 当时的说明。）`GET /api/audit/invariants` 仅在 sandbox profile 注册，返回 `{checkedAt, violations:[{invariant,detail}]}`，checkedAt 来自注入 Clock，沿用本地只读接口无额外 HTTP 鉴权的约定。为遵守 ADR-0014，新增向后兼容的 `LedgerAudit.readonly(DataSource)` 装配入口，在 readonly 凭证的 REPEATABLE READ 事务里对现有 `ledger.v_*` 视图运行同一套 `check()` SQL；分组显式列出视图所需字段，默认 app 审计路径结果不变，没有复制检查逻辑或放宽权限。仅检查 LedgerAudit 已有的 INV-1～5、INV-8；reconciliation、refunds、payouts 没有现成公开审计入口，未新增其检查逻辑。离线脚本单测及 MockMvc＋真实 PostgreSQL 覆盖解析、脱敏、幂等恢复、负例签名、限时等待、空库、正常记账、损坏余额和 readonly 权限。

**T07 实现备注（2026-11-06，AC-8.6、8.8、8.9）**：`accept.sh --only ac-8.8|ac-8.6|ac-8.9`（模块 `payout_refund_recon.py`，纯函数有 `test_payout_refund_recon.py` 离线单测，`selftest.py` 一并运行）。三者的退出码：0 通过、1 失败、75 等待。每次运行落 `target/sandbox/accept-ac8x-<UTC时间>.json`。

- **ac-8.8**：先取 `check-payout` 的就绪 payout，再做银行 sync＋逐日 CSV 导入（复用 catchup），然后对每个 `po_`：批次存在且恰有一条 `BATCH_SETTLED` 分录；`payoutAmount`＝Stripe payout 金额＝批次净额；Stripe `balance_transactions?payout=` 翻完全部分页，去掉 `type=payout` 行后与批次行按 txn 逐项相等（charge/payment→PAYMENT，refund/payment_refund→REFUND，其他类型一律算差异）。HELD 判失败并打印 hold_reason 与历史里的原因对象，不改账本。其余都通过但批次还是 CONFIRMED（模拟银行下一个工作日才入账）输出“等待银行入账”并退出 75。首次实跑：27 行零差异，银行流水为空，退出 75。
- **ac-8.6**：从本地已结算批次的行里按编号顺序取两笔支付，退款幂等键和 `merchant_refund_ref` 固定为 `t07-ac86-a/b`（重跑复用同两笔，不新建）。a：退 100 分到 `SUCCEEDED`，Stripe 按 charge 翻页恰一笔且 `metadata.ledgerlab_refund_id` 等于本地退款号、`re_` 与本地一致。b：relay 出站规则 `POST /v1/refunds` DROP_RESPONSE times=1；证据是命令观察到 `UNKNOWN`、退款历史有一条 `UNKNOWN`、第一次尝试 HTTP 504、第二次（先查询）200，Stripe 上仍恰一笔，relay stats 的 DROP_RESPONSE 增量为 1；结束或失败都清空计划。首次实跑通过。
- **ac-8.9**：处理方快照接口按营业日取数，所以脚本从窗口起点的多伦多日期逐日到今天各导入一份快照（实跑 18 份全部 ACCEPTED），不是一次覆盖整个窗口。对账 cutoff 只接受多伦多零点（接口校验 `Toronto midnight cutoff <= evidenceAsOf <= now - evidenceGrace`），所以用当前多伦多日的零点，**与任务说明里的“当前整点”不同，已按接口约束做**；evidenceAsOf 取快照之后的整秒，等应用时钟超过证据宽限再跑。ALL 持久化运行一次，再用相同参数 dryRun 重放，比较输入哈希、cutoff、evidenceAsOf 和差异指纹，必须相同。差异逐条打印，subject 是本地 `pay_…` 时必须能在证据里追到 Stripe 编号（`po_/txn_/ch_/re_/evt_`），追不到才失败。首次实跑：28 条差异，全部可追到 Stripe 编号，重放一致（见 9.9）。

**实测后修正的口径（T07 偏离，T08 审查接受，2026-11-06）**：

- 对账截止时点（cutoff）取多伦多当日零点（接口只接受多伦多零点），不是“当前整点”。
- 处理方快照按营业日逐日导入（从窗口起点的多伦多日期到今天各一份），不是一次覆盖整个窗口。
- 差异的对象编号可以是 Stripe 前缀（`po_`、`txn_`、`ch_`、`re_`），也可以是本地编号但能在证据里追到 Stripe 编号，两者都算“能追到”。

### 9.9 已知差异（随实测补充）

| 项目 | fake Stripe | 真实 Stripe 测试模式 |
|---|---|---|
| PaymentIntent 创建参数（T01，2026-11-02） | fake Stripe 不实现 PaymentIntent，无法暴露这个差异 | 当前测试账号拒绝显式 `payment_method_types`；9.3 已按审查改用 automatic payment methods、禁止 redirect 并断言 charge 类型 card，两笔支付实测成功 |
| 本地 webhook 监听鉴权（T01，2026-11-02） | fake Stripe 直接发签名 webhook，不需要 Stripe CLI 登录 | CLI 1.55.6 用当前受限密钥获取签名密钥失败（`PERMISSION_ERROR`）；需要用户本人登录后再测 |
| 国际卡手续费（T01，2026-11-02） | 默认按合同公式计算，故障计划可覆盖 fee；不模拟卡国家费率 | 美国 bypass 卡 CAD 1234 分 fee=76，比国内公式 66 多 10；加拿大卡 CAD 10000 分 fee=320，与合同相等。只记录实际差额，不推导完整国际卡费率 |
| 资金可用时间（T01，2026-11-02） | 默认 availability-delay=PT0S；可显式配置延迟，使用模拟时钟 | bypass 净额 1158 分立即 available；加拿大卡净额 9680 分 pending、available_on=1794182400，不应要求普通卡立即可用 |
| 国内费用公式对真实批次的差异（T07，2026-11-06） | 批次与 fake Stripe 公式一致时无 FEE_MISMATCH | 对账 PROCESSOR 对 payout `po_…` 的 27 笔支付全部报 `FEE_MISMATCH`（本地期望按国内公式、批次行是 Stripe 实际费用；该 payout 的支付大多是美国 bypass 卡，见“国际卡手续费”一条）。不是账本错误：批次净额与 Stripe payout 金额相等。真实对账会如实报这类差异。T08 逐笔核对：27 笔都是美国 visa，Stripe 费用 = `30 + round(3.7%)`，无例外（见第 7 节第 2 条） |
| 探测阶段的未结算支付（T07，2026-11-06） | 无 | T01 探测创建的一笔支付（待结算余额）不在任何 payout 内，AGING 报 `PAYMENT_NOT_SETTLED`；等其进入后续 payout 才会消失 |
| 退款 pending（T07，2026-11-06） | `force_pending` 可构造 pending | 余额充足时两笔 100 分退款都直接 `succeeded`，未观察到 pending |
| payout 首次出现时间（T07，2026-11-06） | 按需创建 payout，可立即生成 | 首个自动 payout 在最后一笔 charge 之后约 71.6 小时出现；`payout.created` 与 `reconciliation_completed` 同一秒，`paid` 约 5 分钟后 |
| 退款事件序列（T08，2026-11-06） | 直接成功只发 `refund.created(succeeded)`；不发 `refund.updated` | 同一笔退款先 `refund.created`（`destination_details.card.reference_status=pending`），紧接着 `refund.updated`（status 仍是 succeeded，卡参考号填上、`reference_status=available`）。AC-8.6 带着这两个真实事件实测通过、不变量零违反。未改 fake：改了会动 P3 既有测试的事件序列，需要单独决定 |
| payout 创建时的状态（T08，2026-11-06） | `payout.created` 的 status 是 `pending`、`reconciliation_status` 是 `in_progress`，要等 `reconcile` 动作才 `completed` | `payout.created` 时 status 已是 `in_transit`、`reconciliation_status` 已是 `completed`（同一秒又来 `reconciliation_completed`）。契约测试断言了两边的这个差异；第 7 节第 8 条已说明批次触发保留了查询 payout 对象的后备路径；未改 fake |
| payout 明细分页（T08，2026-11-06） | 按 limit 分页，可注入第 N 页失败和重复行 | 夹具只有一页（28 行，包含 payout 自己的一行），真实多页行为未录到；分页正确性由适配器单测和 fake 的分页故障覆盖 |
| 退款对象多出的字段（T08，2026-11-06） | 只有 id、charge、amount、status、metadata 等所用字段 | 真实对象还带 `balance_transaction`、`destination_details`、`payment_intent`、`payment_method`、`receipt_number` 等；`used-fields.json` 只列我们用到的字段，多出的字段被忽略 |
