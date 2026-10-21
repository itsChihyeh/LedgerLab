# 状态机

P0 设计稿 v0.1，2026-10-21。分录名见 [money-model.md](money-model.md) 第 3 节。

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
| — | `CAPTURED` | webhook `charge.succeeded` | `PAYMENT_CAPTURED` |
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
| `READY` | `SUBMITTED` | 出站命令拿到处理方的受理回执（处理方退款号） | — |
| `READY` / `SUBMITTED` | `UNKNOWN` | 命令超时、连接断开、5xx | — |
| `UNKNOWN` | `SUBMITTED` | 用同一个退款号重试或查询，处理方返回已受理 | — |
| `READY` / `SUBMITTED` / `UNKNOWN` | `SUCCEEDED` | webhook `refund.updated`（status=succeeded），或结算批次里出现这笔退款（先到者生效）。`READY` 出发的情况是：处理方已执行，webhook 比我们记下回执还早 | `REFUND_EXECUTED` |
| `READY` / `SUBMITTED` / `UNKNOWN` | `FAILED` | webhook `refund.failed`，或查询结果明确为失败 | `REFUND_FAILED` |

- `REQUESTED` 只是受理事务里的瞬时状态，不会提交到数据库。
- `READY` 到 `SUBMITTED` 由出站命令驱动（第 6 节）。`READY` 和命令记录在同一事务里建立。
- `UNKNOWN` 没有超时出口。老化检查会对停留过久的 `UNKNOWN` 报警，但只有处理方的明确结果能让它离开。
- 已执行后又被处理方撤销的退款不在首版范围内，写进“已知不做”。

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
| — | `RECEIVED` | webhook `payout.created` 存入，适配器拉到批次行 | — |
| `RECEIVED` | `CONFIRMED` | 逐项核对通过 | `BATCH_SETTLED` |
| `RECEIVED` | `HELD` | 有行对不上本地记录（不认识的支付、金额不同、退款没记、行已属于别的批次、行类型不支持） | — |
| `CONFIRMED` | `PAYOUT_FAILED` | webhook `payout.failed`，且银行没有对应入账 | `BATCH_PAYOUT_FAILED` |
| `HELD` | `RECEIVED` | 引用的对象到齐或纠错完成后重新核对 | — |
| `CONFIRMED` | `BANKED` | 银行流水入账行按批次号精确匹配，金额相等 | `BANK_DEPOSIT_RECEIVED` |

- Stripe `payout.paid` 只是处理方说“应该到了”，记进状态历史作为证据，不改状态、不记账；现金只认银行流水。
- `PAYOUT_FAILED` 之后银行又出现入账：开 `BATCH_STATE_CONFLICT`，不自动记账。

- 批次里出现本地还在 `SUBMITTED`/`UNKNOWN` 的退款：批次本身就是执行证据，同一事务里先记 `REFUND_EXECUTED`，再核对批次。
- 同一个 `batch_id` 再次到达但内容哈希不同：不覆盖，原批次保持原状，新内容存为冲突版本，开差异 `BATCH_VERSION_CONFLICT`。
- 批次里有不支持的行类型（例如拒付）：`HELD`，原因 `UNSUPPORTED_LINE`，页面显示批次不完整。
- 批次里出现本地根本没有的支付：先停在 `HELD`，等对应 webhook（乱序）；超过老化阈值报 `MISSING_IN_LEDGER`。

## 5. 入站事件 Inbox（R6）

```
（验签失败 → 401，不入库）
RECEIVED ──处理成功──▶ PROCESSED
   │ ▲
   ▼ │ 依赖对象到达
PARKED（依赖的对象还不存在，例如退款事件先于支付事件到达）
RECEIVED ──同一 event_id、不同内容──▶ REJECTED（内容冲突，告警）
RECEIVED ──事件类型不支持（例如拒付）或字段越界──▶ REJECTED（UNSUPPORTED / INVALID，告警，页面可见）
RECEIVED ──处理时出现非预期错误──▶ RECEIVED（attempts+1，退避重试；超过上限变 DEAD，告警）
```

- 接收端：先用原始请求体验 HMAC 和时间戳窗口，再按 `event_id` 插入 inbox（唯一约束），然后返回 2xx。重复投递命中唯一约束，内容哈希一致就直接 2xx。
- 处理器按 `received_at` 顺序取事件，加租约，处理成功把事件标 `PROCESSED`，和业务状态、分录在同一事务里提交。
- `PARKED` 事件在它依赖的对象创建时被同一事务唤醒（回到 `RECEIVED`），后台也会定期扫描。

## 6. 出站命令 Outbound command（R5）

退款提交和提现提交共用一张窄命令表。

| 状态 | 含义 |
|---|---|
| `PENDING` | 和业务状态同一事务建立，等待发送 |
| `IN_FLIGHT` | 某个 worker 持有租约（`lease_until`），正在调用外部 |
| `UNKNOWN` | 调用结果不明，等待下一次重试或查询（`next_attempt_at`，指数退避加抖动） |
| `DONE` | 拿到明确回执（成功受理或明确拒绝），回执已写回业务对象 |

- 每条命令有固定的 `request_id`（退款号或银行请求号），**所有重试都用同一个**，外部按它去重。
- 租约过期的 `IN_FLIGHT` 会被别的 worker 接管，接管者先查询再决定是否重发。
- 命令本身没有“失败放弃”状态。重试次数超过上限只会告警并停止自动重试，业务对象保持 `UNKNOWN`，等待人工查询或流水证据。

## 7. 对账差异 Reconciliation issue

| 从 | 到 | 触发 |
|---|---|---|
| — | `OPEN` | 对账运行或老化检查发现差异 |
| `OPEN` | `EXPLAINED` | 后续证据到达，差异自动消失（例如跨日的行在第二天出现）；记下解释它的证据 |
| `OPEN` | `RESOLVED` | 运营处理完成（例如走了受限纠错），必须关联纠错分录或说明 |
| `OPEN` | `ACCEPTED` | 运营确认无需处理（必须写原因） |

同一差异的识别键是 `(category, subject_type, subject_id)`，重复运行对账不会生成重复差异。
