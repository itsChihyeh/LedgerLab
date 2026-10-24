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

**当前实现（P2 T05）**：`charge.succeeded` 调用请款服务；`payout.created`、`payout.reconciliation_completed`、`payout.paid` 经 ingestion 映射后调用 settlement。`APPLIED`、`DUPLICATE_FACT` 对应 inbox `PROCESSED`，其他请款结果对应 `REJECTED`；paid 的依赖未到时为 `PARKED`。`charge.dispute.*` 为 `REJECTED / UNSUPPORTED_TYPE` 并开 `UNSUPPORTED_EVENT`；其余类型暂为 `IGNORED`、`result=NULL`，包括 refund 和 `payout.failed/canceled`。后两类由 T06 接入，refund 由 P3 接入；下表保留相应阶段的目标行为。所有类型的 `livemode=true` 优先拒绝为 `LIVEMODE` 并开一条 `LIVEMODE_EVENT`；charge 交给请款服务判定（事件或 Charge 任一为 live 即拒绝），其他类型在分流前判定，避免重复开差异。

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
2. 测试卡 `4000 0000 0000 0077` 能让资金直接进入可用余额，便于快速产生 payout。它是美国卡，不能用来验证加拿大国内卡费率；费率要用加拿大测试卡另测。
3. 加拿大国内测试卡的手续费等于 `30 + round(2.9%)`；舍入方式不同就改合同公式，不加容差。
4. 余额不足时退款进入 `pending` 的行为在测试模式下可复现（官方文档说卡退款只用可用余额、不够时 pending；fake Stripe 已按此建模）。
5. 有能让 payout 失败的加拿大测试银行账户号。
6. webhook 端点返回非 2xx 时的重试节奏（只用于报告，不写进业务逻辑）。文档说 live 模式最多重试 3 天，sandbox 只在几小时内重试几次，不能依赖测试模式的自动补投。
7. 测试模式的接口速率上限，决定流量生成器和演示页的限流值。
8. 测试模式的自动 payout 会走到 `reconciliation_status=completed` 并发出 `payout.reconciliation_completed`。这一条决定 P2 的批次触发方式：P2 先按官方文档在 fake Stripe 里实现；如果那时测试账号已经注册，就在 P2 开工前先验证这一条，否则 P6 接账号后第一个验证。
9. 用 Stripe 的 default test-mode sandbox 还是新建的独立 sandbox：接账号时选定一种并写在这里，文档里统一称“测试模式”。
   **已定（2026-10-23）**：用户注册了加拿大账号，使用 Stripe 自带的默认测试沙盒（Dashboard 显示“测试企业 沙盒”），不另建 sandbox。开发用受限密钥 `ledgerlab-dev`（`rk_test_…`，只放在本地 `.env` 的 `STRIPE_API_KEY`），权限：Balance、Balance Transaction Sources、Events、Payouts 读取；Charges and Refunds、Payment Intents 写入，其余全部无。第 1～8 条尚未实测。第 8 条原定 P2 开工前实测；2026-10-23 改为 P2 期间做（新账号的首次自动 payout 要等 Stripe 的初始等待期，开工前等不到），P2 先按官方文档在 fake Stripe 里实现 `reconciliation_completed` 触发，并保留“查询 payout 对象看到 `completed`”的后备路径，所以实测结果即使相反也只改触发方式、不改批次核对。

## 8. 协议版本（P1 开工前固定）

- Stripe API 版本固定为 **`2026-09-30.endive`**（2026-10-21 核对的当前版本；命名规则是发布日期加代号）。fake Stripe 的事件和对象形状、录制夹具、webhook 端点的 API 版本都用它。
- P1b 自行实现 HMAC-SHA256 验签，不引入 `stripe-java`；P3 需要出站调用时再选用与该 API 版本对应的 SDK 并固定版本。升级 API 版本是一次单独的改动，要重录夹具并跑契约测试。
- 事件格式用 snapshot 事件（带完整对象），不用 thin 事件。
- 验签：用原始请求体按 Stripe 方案算 HMAC；时间窗口**两侧**都检查（`|now − t| ≤ 300 秒`）。SDK 的验签函数只拒绝过旧的时间戳，未来时间要我们自己补检查。
- P1b 配置为 `ledgerlab.stripe.webhook-secret`（`STRIPE_WEBHOOK_SECRET`，必填且以 `whsec_` 开头）和 `ledgerlab.stripe.api-key`（`STRIPE_API_KEY`，可空，非空须以 `sk_test_` 或 `rk_test_` 开头）；启动时校验且错误不包含密钥值。签名有效但 JSON 不合法或缺少 `id/type/api_version/livemode/created` 时返回 400、不入库。
- P1 的 fake Stripe 只需要 `charge.succeeded`；事件 JSON 按上述版本的官方对象定义写，P6 用真实录制夹具对照（第 6 节）。
