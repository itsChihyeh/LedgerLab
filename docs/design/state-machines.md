# 状态机

P0 设计稿 v0.2，2026-10-21（同日开工前审查修订）。分录名见 [money-model.md](money-model.md) 第 3 节。

通用规则：

- 状态只能沿表里列出的边前进；任何其他转换都返回错误并记日志，不改数据。
- **状态改变和对应的分录在同一个数据库事务里提交**。表里“分录”一栏为空的边不记账。
- 每条边都是幂等的：重复收到同一个证据（同一事件编号、同一流水编号）时，状态不变、不再记账，返回原来的结果。
- 终态不再出边，唯一例外是 `PAID → RETURNED`（银行可以在付款几天后退回）。
- 每个对象保留状态历史表（`*_transitions`）：从哪个状态到哪个状态、证据来源和编号、发生时间、记录时间。

---

## 1. 支付 Payment

我们只看到已请款的支付，授权阶段不在范围内。

| 从 | 到 | 触发 | 分录 |
|---|---|---|---|
| — | `CAPTURED` | webhook `charge.succeeded`，通过请款准入检查（stripe-integration 第 2 节） | `PAYMENT_CAPTURED` |
| `CAPTURED` | `SETTLED` | 所在批次 `BATCH_SETTLED` 记账 | （包含在批次分录里） |
| `CAPTURED` | `VOIDED` | 受限纠错撤销误记的请款（只限未结算） | `REVERSAL` |

退款进度不作为支付状态，而是派生字段：`refund_state ∈ {NONE, PARTIAL, FULL}`，按未失败、未取消的退款合计计算。

## 2. 退款 Refund（I1、S3）

```
REQUESTED ──受理──▶ AWAITING_FUNDS ──资金覆盖──▶ READY ──命令发出──▶ SUBMITTED ──▶ SUCCEEDED
                         │                                     │  ▲
                         └──运营取消──▶ CANCELLED              ▼  │ 查询/重试
                                                             UNKNOWN
                                         SUBMITTED / UNKNOWN ──处理方确认失败──▶ FAILED
```

| 从 | 到 | 触发 | 分录 |
|---|---|---|---|
| — | `AWAITING_FUNDS` 或 `READY` | 退款 API 受理（INV-7 通过）；按 money-model 4.1 判断是否放行 | `REFUND_ACCEPTED` |
| `AWAITING_FUNDS` | `READY` | 2102 增加后重算队列，进入可放行前缀 | — |
| `AWAITING_FUNDS` | `CANCELLED` | 运营取消（必须写原因） | `REFUND_CANCELLED` |
| `READY` | `SUBMITTED` | 出站命令拿到处理方的回执且退款对象 `status=pending`（处理方已受理，在等可用余额） | — |
| `READY` / `SUBMITTED` | `UNKNOWN` | 命令超时、连接断开、5xx | — |
| `UNKNOWN` | `SUBMITTED` | 查询或重试得到退款对象，`status=pending` | — |
| `READY` / `SUBMITTED` / `UNKNOWN` | `SUCCEEDED` | 任何“退款对象 `status=succeeded`”的证据：出站调用的响应、`refund.created`/`refund.updated` 事件、查询结果；或结算批次里出现这笔退款（先到者生效）。`READY` 出发的情况是：处理方已执行，证据比我们记下回执还早 | `REFUND_EXECUTED` |
| `READY` / `SUBMITTED` / `UNKNOWN` | `FAILED` | 退款对象 `status=failed` 或 `canceled`（事件、查询结果或调用响应）；或调用被处理方明确拒绝（4xx，退款没有创建） | `REFUND_FAILED` |

- `REQUESTED` 只是受理事务里的瞬时状态，不会提交到数据库。
- `READY` 到 `SUBMITTED` 由出站命令驱动（第 6 节）。`READY` 和命令记录在同一事务里建立。
- `UNKNOWN` 没有超时出口。老化检查会对停留过久的 `UNKNOWN` 报警，但只有处理方的明确结果能让它离开。
- 已执行后又被处理方撤销的退款（Stripe 测试里有“先成功后失败”的路径）不在首版范围内：`SUCCEEDED` 之后再收到失败证据，不改状态、不记账，开差异 `REFUND_STATE_CONFLICT`，页面可见。

## 3. 提现 Payout（S1）

```
RESERVED ──命令发出──▶ SUBMITTED ──银行回执──▶ ACCEPTED ──银行流水出账──▶ PAID ──银行流水退回──▶ RETURNED
   │                      │  ▲                    │
   │                      ▼  │ 查询/重试           │
   │                    UNKNOWN                   │
   └──银行确认拒绝/取消（且流水里没有出账）─────────┴──▶ FAILED
```

| 从 | 到 | 触发 | 分录 |
|---|---|---|---|
| — | `RESERVED` | 提现 API 受理（money-model 4.2 两个门槛都通过） | `PAYOUT_RESERVED` |
| `RESERVED` | `SUBMITTED` | 出站命令已发出、银行确认收到请求 | — |
| `RESERVED` / `SUBMITTED` | `UNKNOWN` | 超时、连接断开、5xx、回执丢失 | — |
| `UNKNOWN` | `SUBMITTED` / `ACCEPTED` | 用同一个银行请求号重试或查询 | — |
| `SUBMITTED` | `ACCEPTED` | 银行返回“已受理，将于某日付款” | — |
| `RESERVED` / `SUBMITTED` / `UNKNOWN` / `ACCEPTED` | `PAID` | 银行流水出现出账行，按银行请求号精确匹配 | `PAYOUT_PAID` |
| `RESERVED` / `SUBMITTED` / `UNKNOWN` / `ACCEPTED` | `FAILED` | 银行**确认**拒绝或取消，且截至当时的银行流水里没有这笔出账 | `PAYOUT_FAILED` |
| `PAID` | `RETURNED` | 银行流水出现退回行，关联原请求号 | `PAYOUT_RETURNED` |

- **超时绝不等于失败**。`UNKNOWN` 只有两个出口：重试/查询得到明确结果，或者银行流水给出证据。
- 银行查询返回“查无此请求”时，用同一个请求号重新提交（银行按请求号去重），状态仍是 `UNKNOWN`，不释放资金。
- 冲突证据：状态已是 `FAILED`，银行流水却出现出账行 → 不自动记账，开差异 `PAYOUT_STATE_CONFLICT`，走受限纠错。
- 银行的 API 状态（`ACCEPTED`）和银行流水是两种证据，模拟器里分开产生（I6）；只有流水能移动现金账户。

## 4. 结算批次 Settlement batch（S2）

| 从 | 到 | 触发 | 分录 |
|---|---|---|---|
| — | `ANNOUNCED` | webhook `payout.created`：记下 payout 编号、金额、预计到账日；做 payout 准入检查 | — |
| `ANNOUNCED` | `RECEIVED` | 明细就绪（`payout.reconciliation_completed` 或查询到 `reconciliation_status=completed`），全部分页拉完，行合计等于 payout 金额 | — |
| `ANNOUNCED` | `HELD` | payout 不符合准入（手动、即时、负 payout、`reconciliation_status=not_applicable`），原因 `UNSUPPORTED_PAYOUT` | — |
| `RECEIVED` | `CONFIRMED` | 逐项核对通过 | `BATCH_SETTLED` |
| `RECEIVED` | `HELD` | 有行对不上本地记录（不认识的支付、金额不同、退款没记、行已属于别的批次、行类型不支持） | — |
| `CONFIRMED` | `PAYOUT_FAILED` | webhook `payout.failed`／`payout.canceled`，且银行没有对应入账 | `BATCH_PAYOUT_FAILED` |
| `HELD` | `RECEIVED` | 引用的对象到齐或纠错完成后重新核对 | — |
| `CONFIRMED` | `BANKED` | 银行流水入账行按批次号精确匹配，金额相等 | `BANK_DEPOSIT_RECEIVED` |

- Stripe `payout.paid` 只是处理方说“应该到了”，记进状态历史作为证据，不改状态、不记账；现金只认银行流水。
- `PAYOUT_FAILED` 之后银行又出现入账：开 `BATCH_STATE_CONFLICT`，按精确匹配失败记 `UNAPPLIED_CASH_RECEIVED`（2026-10-23 改：让账上现金等于银行，钱归属留给受限纠错），批次状态不变。
- 银行入账比批次确认先到：流水行 `WAITING`，批次 `CONFIRMED` 的同一事务里重新匹配，匹配上就同时记 `BANK_DEPOSIT_RECEIVED`、批次直接到 `BANKED`（money-model 第 3 节）。

- 批次里出现本地还在 `SUBMITTED`/`UNKNOWN` 的退款：批次本身就是执行证据，同一事务里先记 `REFUND_EXECUTED`，再核对批次。
- 同一个 `batch_id` 再次到达但内容哈希不同：不覆盖，原批次保持原状，新内容存为冲突版本，开差异 `BATCH_VERSION_CONFLICT`。只有“明细就绪且拉完”的快照才算一个版本；未就绪或没拉完的抓取只存档，不参与比较。
- 先到 `payout.reconciliation_completed`、后到 `payout.created`（乱序）：直接按就绪处理，`created` 到达时只记状态历史。
- 批次里有不支持的行类型（例如拒付）：`HELD`，原因 `UNSUPPORTED_LINE`，页面显示批次不完整。
- 批次里出现本地根本没有的支付：先停在 `HELD`，等对应 webhook（乱序）；超过老化阈值报 `MISSING_IN_LEDGER`。

### P2 T05 实现备注：批次确认与入站恢复

以下记录 T05 交付时的行为；版本比较、HELD 恢复、失败事件和随机模型已由下方 T06 备注更新。

- `settlement.BatchSettlement` 是批次业务入口，依赖 payments 的窄查询／状态更新接口和 ledger；ingestion 负责 HTTP、证据映射和调度，settlement 不反向依赖 ingestion。每次业务处理先取 `ledger.lockFunds()`，再锁批次；不存在的批次也由资金锁串行化建立。
- `ANNOUNCED → RECEIVED → CONFIRMED`、五项合计、已核对明细、支付 `CAPTURED → SETTLED`、支付历史和一个 `BATCH_SETTLED` 在同一业务事务提交。V6 要求批次先成为 `CONFIRMED` 才可插入明细，事务内按此顺序写入。`RECEIVED` 是这条成功路径的事务内状态；**AC-5.16 在第三条 posting 后故障时回滚到原有的 `ANNOUNCED`，不是持久化的 `RECEIVED`**。若本次就绪事件首次建立批次，失败连新批次也一起回滚；独立提交的 fetch 存档保留。重试重新拉取、核对和记账。
- `payout.paid` 只追加一行当前状态到当前状态的历史；批次尚不存在时 inbox `PARKED`，`parked_on = batch:{po_…}`。建批次与唤醒同依赖的 PARKED 事件在同一事务，唤醒后为 `RECEIVED`、清 `parked_on` 并立即可重试。停放与建批次都在资金锁保护下，避免并发丢失唤醒。此规则同样适用于 T06 的 `payout.failed`／`payout.canceled`；这两类在 T05 仍为 `IGNORED`。PARKED 老化扫描留给 T11。
- 已存在批次的 created，以及非 `ANNOUNCED` 批次的再次就绪，追加证据历史并返回 `DUPLICATE_FACT`。重复 created 即使快照已 completed 也不再拉取，不让 HTTP 故障阻塞已知重复；已确认批次也可在拉取前短路。并发拉取仍需在业务事务重新查状态，保证只记一次。T05 不实现内容冲突版本比较或 HELD 自动恢复，留给 T06。
- `HELD` 是已成功处理证据的业务结果，inbox 为 `PROCESSED/APPLIED`。T05 对不支持的 payout 开 `UNSUPPORTED_PAYOUT`、`INGESTION`、batch 主体差异；其余行核对失败先记录 `hold_reason`，差异分类与恢复留给 T06。本地支付缺失先 `HELD(UNKNOWN_PAYMENT)`，不把暂时乱序立即报为 `MISSING_IN_LEDGER`。REFUND 和未知行暂以 `UNSUPPORTED_LINE` 隔离；已知 `PAYOUT_FAILED` 批次的同额、零手续费退回行可确认（AC-5.18），不能在另一批次再次使用。
- 新增 `SettlementWorkerTest` 真库／本地 HTTP 场景覆盖 AC-5.1、AC-5.16–5.18、AC-1.4b、AC-2.8 及 paid 停放恢复。故障测试使用透传 JDBC 包装，在真实写入第三条 posting 后抛异常，不改生产账本接口。`SettlementModelProperties` 增加独立纯 Java 批次参考模型和 jqwik 有状态动作：created、created 已就绪、就绪、paid、不支持的 payout；随机排列和新 event_id 重复，每步比较状态、历史、余额、posting 数并查账本不变量。固定种子 `2026102305`，100 条序列、每条最多 16 个动作；P1 原有模型保留：本地默认 100 条，CI 的 1000 条配置不变。

### P2 T06 实现备注：核对失败、重核、版本与打款失败

- HELD 沿用 inbox `PROCESSED/APPLIED`，不新增 result 或数据库枚举。核对失败不插入批次行、不记批次分录；原文固定保存在 `lines_fetch_id` 指向的完整 `io.processor_fetch` 中。差异为 `detected_by=INGESTION`、subject 为 batch，`expected`／`actual` 保存核对值、行身份和处理方引用，`evidence_refs` 关联入站事件／拉取存档。
- 沿用现有差异类别：`AMOUNT_MISMATCH`、`FEE_EXCEEDS_GROSS` 均报 `AMOUNT_MISMATCH`（费用超过毛额是金额契约违规）；`UNSUPPORTED_LINE` 报 `UNSUPPORTED_EVENT`；`LINE_IN_OTHER_BATCH` 报 `DUPLICATE_STATEMENT_LINE`；`TOTAL_MISMATCH` 报 `BATCH_TOTAL_MISMATCH`。具体原因仍在批次 `hold_reason` 和差异 `actual.reason` 中。`UNKNOWN_PAYMENT` 不立即开差异，留给 T11 老化。实际手续费与合同手续费不同仍按实际值结算，T10 对账再报 `FEE_MISMATCH`。
- ingestion 在 `PaymentCapture.capture` 返回 `APPLIED`／`DUPLICATE_FACT` 后，同一事务同步调用 settlement 的按支付号重核入口。按批次号锁定 `HELD(UNKNOWN_PAYMENT)` 候选，读取其固定 `lines_fetch_id` 的完整存档，仅重核含该支付号的批次；不发 HTTP。多笔支付尚未到齐时继续 HELD；到齐后 `HELD → RECEIVED → CONFIRMED`，清 `hold_reason`，支付与批次记账一起提交或回滚。其他 HELD 原因不自动恢复。
- 已 `CONFIRMED`／`BANKED`／`PAYOUT_FAILED` 的再次就绪事件完整拉取后比较规范化版本：跳过 `type=payout` 转账行，按 `balance_txn_id` 排序，对行身份、类型、引用、gross／fee／net、转小写的币种、业务时间 epoch seconds 的 JSON 数组算 SHA-256，另比较 payout 金额。相同为 `PROCESSED/DUPLICATE_FACT`；不同为 `REJECTED/BUSINESS_CONFLICT`，开 `BATCH_VERSION_CONFLICT`，新 fetch 即冲突版本存档。原批次字段、明细和分录不改，只追加证据历史；同一差异识别键不重复建 issue。原始字节哈希仅用于存档完整性。未就绪或不完整拉取不参与比较；重复 created 仍只记历史。
- `payout.failed`／`payout.canceled` 不拉明细：批次不存在时 `PARKED`；`ANNOUNCED`／`HELD` 只记历史、`PROCESSED/APPLIED`，保留 HELD 原因；`CONFIRMED` 同事务转 `PAYOUT_FAILED`，按已核对 net 记 `BATCH_PAYOUT_FAILED`（借 1101／贷 1102），source key 为 `batch:{id}:payout_failed`；重复失败为 `PROCESSED/DUPLICATE_FACT`。`BANKED` 时仅记历史、开 `BATCH_STATE_CONFLICT`，inbox `REJECTED/BUSINESS_CONFLICT`。之后退回行仍须匹配失败批次的金额、币种、状态且未被其他批次使用；样例 H 使用真实失败事件建立前置。
- `SettlementWorkerTest` 覆盖 AC-5.2、5.3、5.19、4.1、1.4b、5.18，包括规范化去重、冲突重复告警、不完整版本、请款与重核回滚、失败与分录回滚、BANKED 冲突。`SettlementModelProperties` 的独立纯 Java 模型新增请款、变更版本、失败／取消、退回重发、跨批次复用、异常费用和不支持行；从 S0 或 S1 开始随机组合，每步检查账本不变量、INV-9 的 2101／1102、余额、状态、hold reason、历史、明细／posting 数和差异身份。重发动作要求原批次已失败，跨批次复用要求已结算；其余可任意乱序，使用新 event_id 重复事实。种子 `2026102306`，最多 24 步，次数跟随 Maven 的 jqwik 配置（本地 100、CI 1000），动作执行／前置不满足计数和状态分布写入 Surefire 的 jqwik 报告。并发与事务故障仍由受控定向测试覆盖，不把随机模型当作并发证明。

## 5. 入站事件 Inbox（R6）

**状态**（处理进度）和**结果**（处理出了什么）分开存：`status` 只有下面几种；`result` 记 `APPLIED`、`DUPLICATE_FACT`、`BUSINESS_CONFLICT`、`INVALID_AMOUNT`、`UNSUPPORTED_CURRENCY`、`UNSUPPORTED_CAPTURE`、`UNSUPPORTED_TYPE`、`LIVEMODE` 等。

```
（验签失败 → 401，不入库）
RECEIVED ──处理完成──▶ PROCESSED（result = APPLIED 或 DUPLICATE_FACT）
   │ ▲
   ▼ │ 依赖对象到达
PARKED（依赖的对象还不存在，例如退款事件先于支付事件到达）
RECEIVED ──事件类型不支持、字段越界、业务冲突、livemode──▶ REJECTED（result 写原因，告警，页面可见）
RECEIVED ──订阅范围外的类型──▶ IGNORED
RECEIVED ──处理时出现非预期错误──▶ RECEIVED（attempts+1，next_attempt_at 退避；超过上限变 DEAD）
```

- **接收事务**：先用原始请求体验 HMAC 和时间戳窗口（`|now − t| ≤ 300 秒`，两侧都查），再按 `event_id` 插入 inbox（唯一约束），然后返回 2xx。重复投递命中唯一约束：内容哈希一致就直接 2xx；**内容不同**时原事件行一个字段都不改，冲突内容另存一行到 `io.inbox_conflict` 并告警，仍返回 2xx（AC-2.4）。
- **抢占事务**：worker 用 `FOR NO KEY UPDATE SKIP LOCKED` 取 `status=RECEIVED`、`next_attempt_at ≤ now` 且没有租约或 `lease_until < now` 的事件，按 `received_at`、`event_id` 顺序；写入 `lease_owner`、`lease_until`，并把 `lease_epoch` 加 1，提交。`core.payment.source_event_id` 外键在 inbox 行上持有 `KEY SHARE`，非键更新锁与它兼容，仍能让抢占者互斥。
- **业务事务**：业务状态、分录、事件的 `status`/`result` 在同一事务里写。最后一步是 `UPDATE … WHERE event_id = ? AND lease_epoch = <抢占时的值> AND status = 'RECEIVED'`；更新 0 行说明租约已被别人接管，整个事务回滚。所以租约过期后旧 worker 迟到的结果不会覆盖接管者的结果，也不会多记一次账。
- 资金锁使旧 worker 在业务事务内持锁时，新 worker 即使已提交抢占事务，也不能先提交业务事务；“新 worker 提交后旧 worker 才恢复”的交错只会发生在旧 worker 进入业务事务之前卡住时。两处均以 epoch 条件写回防止迟到结果生效，更新 0 行会回滚全部业务写入。
- **失败记录事务**：业务事务失败后，单独开事务把 `attempts` 加 1，写回 `last_error`（最多 2000 字符）、`next_attempt_at` 并清租约；条件同时包括抢占时的 `lease_epoch` 和 `status=RECEIVED`，防止旧 worker 改动接管后的进度。P1 超过重试上限时 `DEAD` 只记状态、次数和错误，**不开差异**，留给 P2 的老化检查。
- 默认每 1 秒轮询一次，每批最多 20 条，租约 30 秒；失败退避为 `min(1 秒 × 2^(attempts−1), 5 分钟)`，上限 5 次（第 6 次失败进入 `DEAD`）。参数均在 `ledgerlab.inbox` 下可配；worker 编号为 `owner`（环境变量 `LEDGERLAB_WORKER_ID`，默认 `inbox-1`），测试用 `scheduling-enabled=false` 关闭轮询并直接调用 `processAvailable()`。所有时间来自注入的 `Clock`，`processed_at` 取完成处理时刻；请款证据的 `receivedAt` 始终取 inbox 的 `received_at`。
- 抢占、业务、失败记录各使用独立事务。抢占提交后、业务事务开始前，以及业务写入完成后、epoch 写回前各有一个生产空操作测试钩子，用 latch 或异常控制崩溃和接管顺序。
- P2 T05 在抢占提交后、业务事务开始前增加 payout 明细拉取及 `afterFetch` 测试钩子。HTTP 和 fetch 存档沿用 T04；分页不完整抛出处理失败，进入既有失败事务，不写批次版本或冲突差异。拉取期间租约可被接管，成功与失败仍用原 `lease_epoch` 条件写回；过期 worker 的业务历史、唤醒和分录全部随事务回滚。
- `PARKED` 事件在它依赖的对象创建时被同一事务唤醒（回到 `RECEIVED`），后台也会定期扫描。
- P1 处理 `charge.succeeded`；P2 T05 接入 `payout.created`、`payout.reconciliation_completed`、`payout.paid`。`charge.dispute.*` 拒绝为 `UNSUPPORTED_TYPE` 并开差异；其余类型（含 T06 的 payout.failed/canceled 和 P3 的 refund）暂为 `IGNORED`、结果为空。任何类型的 `livemode=true` 优先拒绝为 `LIVEMODE`；具体映射见 stripe-integration 第 2 节。
- P1 的“告警”和“开差异”写进 `recon.issue`，`detected_by = INGESTION`，不需要对账运行（data-dictionary 第 4 节）；上述 `DEAD` 不开差异。

## 6. 出站命令 Outbound command（R5）

退款提交和提现提交共用一张窄命令表。

| 状态 | 含义 |
|---|---|
| `PENDING` | 和业务状态同一事务建立，等待发送 |
| `IN_FLIGHT` | 某个 worker 持有租约（`lease_owner`、`lease_until`、`lease_epoch`），正在调用外部 |
| `UNKNOWN` | 调用结果不明，等待下一次重试或查询（`next_attempt_at`，指数退避加抖动） |
| `DONE` | 拿到明确回执（成功受理或明确拒绝），回执已写回业务对象 |

- 每条命令有固定的 `request_id`（退款号或银行请求号），**所有重试都用同一个**，外部按它去重。
- 租约过期的 `IN_FLIGHT` 会被别的 worker 接管（`lease_epoch` 加 1），接管者先查询再决定是否重发。旧 worker 迟到的回执按 inbox 同样的 `lease_epoch` 条件写回，写不进去就丢弃，只留在尝试记录里。
- 第一次真正发出请求时写入 `first_sent_at`，之后不再改；每次尝试另记一行 `io.outbound_attempt`（时间、请求、结果或错误）。退款“超过 20 小时只有完整查询确认不存在才重发”的规则以 `first_sent_at` 为准（stripe-integration 第 3 节）。
- 命令本身没有“失败放弃”状态。重试次数超过上限只会告警并停止自动重试，业务对象保持 `UNKNOWN`，等待人工查询或流水证据。

## 7. 对账差异 Reconciliation issue

| 从 | 到 | 触发 |
|---|---|---|
| — | `OPEN` | 对账运行或老化检查发现差异 |
| `OPEN` | `EXPLAINED` | 后续证据到达，差异自动消失（例如跨日的行在第二天出现）；记下解释它的证据 |
| `OPEN` | `RESOLVED` | 运营处理完成（例如走了受限纠错），必须关联纠错分录或说明 |
| `OPEN` | `ACCEPTED` | 运营确认无需处理（必须写原因） |

同一差异的识别键是 `(category, subject_type, subject_id)`，重复运行对账不会生成重复差异。

### 7.1 运行发现与处置状态分开（2026-10-23 定，P2 开工前置 ③）

- **发现**（`recon.finding`）：某次运行看到的一条差异，带当次的 `expected`/`actual`/证据和内容指纹 `fingerprint`（类别、subject、expected、actual 规范化后的 SHA-256）。只追加、永不修改，所以任何一次旧运行的报告都能原样重现。dry run 不写发现。
- **差异**（`recon.issue`）：同一 `(category, subject_type, subject_id)` 的当前处置状态，永远只有一行；每次状态变化另记一行 `recon.issue_transition`（只追加，带运行号、原因和解释它的证据）。

每次正式运行结束时，对**本次运行范围内**（运行类别相同、subject 在 `cutoff_at` 范围内）的每个识别键：

| 差异当前状态 | 本次发现了 | 本次没发现 |
|---|---|---|
| 不存在 | 新建，`OPEN` | — |
| `OPEN` | 保持 `OPEN`，更新 `last_run_id`、最新指纹 | 转 `EXPLAINED`，记下本次运行和解释它的证据（例如出现了的结算单行） |
| `EXPLAINED` | **重开**为 `OPEN`（之前的解释被推翻） | 不变 |
| `ACCEPTED`／`RESOLVED` | 指纹相同：保持关闭，只更新 `last_run_id`；**指纹不同：重开**为 `OPEN` | 不变 |

- 不在本次运行范围内的差异一律不动；`detected_by = INGESTION` 的差异不参与这张表，只能由运营处置。
- `TIMING_DIFFERENCE` 到期升级为老化类差异时，旧差异按“本次没发现”转 `EXPLAINED`，解释写“已升级为 {新类别}”（contracts 1.2 第 8 条）。
- 重开不新建差异编号，便于在工作台看到一条差异的完整历史；`reopen_count` 记重开次数。

### P2 T09 实现备注：运行骨架与运营处置

- `ReconRule` 声明 kind、version，返回发现列表，另以 `checkedScope` 返回实际检查的 `(category, subject_type, subject_id)` 及未发现时的解释和证据。规则负责按 request 的 cutoff/evidence 时点过滤义务和证据；框架要求发现属于该规则的范围，拒绝重复识别键和重叠规则范围。范围精确到类别，避免检查同一 subject 的手续费时误解释另一类差异。真实规则留给 T10/T11，假规则仅存在于测试。
- 自动处置按 first_run_id 关联的 kind 判断归属。范围外、其他 kind 和 INGESTION 的 issue 不更新；若本次发现碰到这些既有识别键，仍保存 finding 并关联原 issue，不改处置历史。ACCEPTED/RESOLVED 同指纹只更新 last_run_id，不刷新 updated_at；OPEN 重复发现只更新 last_run_id、fingerprint、updated_at；重开清除旧 explanation/correction 引用并精确递增 reopen_count，旧说明完整保留在 transition。
- 规范化对 JSON 对象键递归排序、去掉格式空白，保留数组顺序、字符串与 null；数值只接受 long 整数。SHA-256 输入是类别、subject_type、subject_id、expected、actual 的 JSON 数组；证据引用和 due_at 不参与指纹。issue 的首次证据不覆盖，每次运行的发现独立保留。
- 单次正式运行使用独立 REPEATABLE READ 事务：固定输入、计算全部发现、写 run/finding/issue/transition、标 FINISHED 一起提交。任何异常全部回滚；不为失败另开事务。dry run 还执行数据库 READ ONLY，报告 id=null，不生成持久编号。运行与运营处置都锁 issue；并发旧快照遇到状态更新由 PostgreSQL 拒绝，HTTP 返回 409，调用方重试，不覆盖运营结果。
- 公开 `IssueDisposition.accept(issueId, reason)` / `resolve(issueId, note)` 只接受 OPEN 和非空白说明，在同一事务写状态及 OPERATOR transition，run_id=null。重复处置返回状态错误，INGESTION 可由这两个方法处置；本任务不开放处置 HTTP。
- `POST /api/recon/runs` 接收 kind/cutoffAt/evidenceAsOf/dryRun，服务端规则版本取 `ledgerlab.recon.rule-version`（默认 p2-v1），自动选取时点前的就绪输入。`GET /api/recon/runs/{id}` 返回固定输入哈希、摘要及原发现，未知编号 404。证据宽限 `ledgerlab.recon.evidence-grace` 默认 60s、必须为正；两时点校验失败 400。当前无生产规则时可生成空报告，不表示已完成真实对账。
- 定向真库测试覆盖 AC-5.8、AC-5.9 骨架、AC-5.21、DST 时点、角色权限、回滚和受控并发；jqwik 独立纯 Java 模型加入发现/内容变化、消失、范围外、dry run、accept、resolve 动作，固定种子 2026102309、最多 20 步，本地 100 条序列，每步核对状态、重开次数、历史数量及原 posting/余额和账本不变量。
