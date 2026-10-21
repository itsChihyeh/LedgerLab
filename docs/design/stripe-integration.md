# Stripe 测试模式接入

P0 设计稿 v0.1，2026-10-21。决策见 ADR-0016。

目的：让处理方一侧是真实的支付服务商沙箱，而不只是我们自己编的世界。webhook 的重复投递、乱序和签名都来自真实 Stripe；对账用的外部数据来自 Stripe 的 balance transactions 和 payout。全程只用测试模式，不涉及真钱。

---

## 1. 谁扮演什么

| 角色 | 测试 / CI / 故障演示 | 真实沙箱运行 |
|---|---|---|
| 处理方 | **fake Stripe**：模拟器里说 Stripe 协议子集的部分（webhook 格式、签名方案、少量 REST 接口），状态和真值都在 `sim_processor` | **Stripe 测试模式**（用户自己的账号） |
| 进入 LedgerLab 的路径 | 同一个 Stripe 适配器：`POST /webhooks/stripe` ＋ 出站 Stripe REST 客户端 | 同左，只换 base URL 和密钥 |
| 投递故障注入 | **chaos relay**：挡在 webhook 入口前，原样转发、复制、延迟、乱序、丢弃（请求体不改，签名仍然有效；改请求体则签名失败） | 同一个 chaos relay，挡在 `stripe listen` 或公网入口前；再加 Stripe 自己的重试和 `stripe events resend` |
| 出站故障注入 | 模拟器直接按故障计划超时、丢回执 | chaos relay 的出站代理：请求照发给 Stripe，把响应丢掉，制造“对方已执行、我们不知道” |
| 银行 | **模拟银行**（`sim_bank`），首版没有真实银行可接 | 仍是模拟银行；它把 Stripe payout 当成“收到的转账”，自己决定入账日期，并可注入拆分、重复、少到 |
| 产生支付的人 | 模拟器按场景生成 | **流量生成器**：用 Stripe API 和测试卡创建并确认 PaymentIntent |
| 对账的外部证据 | fake Stripe 的 balance transactions / payouts 接口 | 真实 Stripe 的 balance transactions / payouts 接口 |
| 真值（ground truth） | `sim_truth`：注入了什么、预期什么差异 | Stripe API 的状态是处理方侧的事实；chaos relay 日志记录它做了什么；**没有**“注入差异”的真值 |

两点要如实写进 README：

- 测试模式下结算单和 webhook 都来自 Stripe，两者的独立性比模拟器模式弱（真实对账也是如此，处理方的报表就是处理方的话）。对账准确率（误报、漏报）的评测只在模拟器模式做，那里有真值。
- fake Stripe 只实现我们用到的子集，行为以真实 Stripe 为准；两者的差异靠契约测试抓（第 6 节）。

## 2. 事件映射

webhook 端点只订阅下表的事件类型。

| Stripe 事件 | 我们的处理 | 分录 |
|---|---|---|
| `charge.succeeded` | 支付 `CAPTURED`；`processor_payment_ref` = `ch_…`，同时存 `pi_…` | `PAYMENT_CAPTURED` |
| `refund.created` | 只记状态历史（我们发起的退款，对方已受理） | — |
| `refund.updated`（status=succeeded） | 退款 `SUCCEEDED`；按 `metadata.ledgerlab_refund_id` 找本地退款 | `REFUND_EXECUTED` |
| `refund.updated`（status=pending） | 状态历史（Stripe 自己的余额不足等待） | — |
| `refund.failed` | 退款 `FAILED` | `REFUND_FAILED` |
| `payout.created` | 适配器拉取 `GET /v1/balance_transactions?payout=po_…` 作为批次行，核对后批次 `CONFIRMED` | `BATCH_SETTLED` |
| `payout.paid` | 状态历史（处理方说“应该到了”），不动现金 | — |
| `payout.failed` | 批次 `PAYOUT_FAILED` | `BATCH_PAYOUT_FAILED` |
| `charge.dispute.*`、其他资金类事件 | 不支持：inbox `REJECTED(UNSUPPORTED)`，开差异，页面可见 | — |
| 未订阅的其他类型 | `IGNORED`（记录但不处理） | — |

- 不在 Stripe 里找不到 `metadata.ledgerlab_refund_id` 的退款（例如有人在 Stripe 后台手动退款）：`REJECTED(UNKNOWN_REFUND)`，开差异 `MISSING_IN_LEDGER`。首版不支持从处理方后台发起的退款。
- `livemode=true` 的事件一律拒绝并告警。
- 批次行的 balance transaction 类型：`charge` → `PAYMENT`，`refund` → `REFUND`，`payout_failure` → `PAYOUT_FAILURE`；其他类型（`adjustment`、`dispute` 等）→ 批次 `HELD(UNSUPPORTED_LINE)`。

拉取批次行是 HTTP 调用，在数据库事务**之外**做：worker 先拉取并把原始 JSON 存进 `io.processor_fetch`，再开事务核对和记账。拉取失败按 inbox 的重试规则退避。

## 3. 出站调用

| 用途 | 接口 | 幂等与恢复 |
|---|---|---|
| 提交退款 | `POST /v1/refunds`（`charge`、`amount`、`metadata.ledgerlab_refund_id`） | `Idempotency-Key` = 退款号 `rfd_…` |
| 查询退款 | `GET /v1/refunds?charge=ch_…`，按 metadata 找 | 结果未知时先查再重发 |
| 批次行 | `GET /v1/balance_transactions?payout=po_…`（分页） | 只读 |
| 对账导入 | `GET /v1/balance_transactions?created[gte]&created[lt]`、`GET /v1/payouts?…` | 只读；按截止时点取数，原始结果存档并算哈希 |

**Stripe 的幂等键至少保留 24 小时，之后可能被清理**（Stripe idempotent requests 文档）。所以退款命令在 `UNKNOWN` 状态下的规则是：**任何重发之前先查询**；距第一次发送超过 20 小时的命令，只有查询确认 Stripe 上没有这笔退款时才允许重发。否则幂等键过期后的重发可能变成第二笔退款。

## 4. 编号与时间

| 我们的字段 | Stripe 来源 |
|---|---|
| `event_id` | `evt_…` |
| `processor_payment_ref` | `ch_…`（另存 `pi_…`） |
| `processor_refund_ref` | `re_…` |
| `batch_id` | `po_…`（所以我们的商户提现用 `mpo_` 前缀） |
| `balance_txn_id` | `txn_…` |
| `occurred_at` | 对象的 `created`（Unix 秒） |
| `signed_at` | `Stripe-Signature` 里的 `t` |
| 批次 `arrival_date` | payout `arrival_date`（处理方预计到账日，不是银行记账日） |
| 手续费 | 支付对应 balance transaction 的 `fee`；合同预期值另算 |

## 5. 密钥与安全

- 只用测试模式密钥：优先用**受限密钥**（`rk_test_…`），只开退款写、charges/refunds/payouts/balance transactions 读。应用启动时检查前缀，看到 `sk_live_` / `rk_live_` 直接拒绝启动。
- `STRIPE_API_KEY`、`STRIPE_WEBHOOK_SECRET` 只从环境变量读：本地放 `.env`（已在 `.gitignore`），部署放 AWS Secrets Manager。仓库里只有 `.env.example`（值为空）。gitleaks 在 pre-commit 和 CI 里拦 `sk_`、`rk_`、`whsec_` 前缀。
- 每个环境一个 webhook 端点、一个签名密钥；本地开发用 `stripe listen --forward-to`，它给的临时签名密钥也只进 `.env`。
- 契约测试用的录制夹具（第 6 节）在提交前脱敏：只保留测试对象编号和金额，去掉账号编号、邮箱等。
- Stripe 账号由用户本人注册，必须是加拿大账号（结算币种 CAD）。

## 6. 契约测试：fake Stripe 和真实 Stripe 不能漂移

- 在测试模式下跑一组固定场景，把收到的 webhook 和 API 响应录下来（脱敏后进仓库）。
- CI 里用这些录制夹具驱动适配器，检查映射结果（不需要密钥、不联网）。
- 同一组场景在 fake Stripe 上也跑一遍，比较两边产生的事件序列和对象字段（只比我们用到的字段）。有差异就以真实 Stripe 为准修模拟器。
- 真实沙箱运行（P6）产出一份运行报告：多少笔支付、退款、payout，chaos relay 注入了多少重复/乱序/丢弃，最终不变量是否全部成立。简历和 README 里的数字只能来自这份报告。

## 7. 开户后要先实测的假设

这些是按文档做的推断，接入前要在测试模式里逐条验证，结果回写本文：

1. 测试模式下自动 payout 会按日程生成，且 `balance_transactions?payout=` 对它可用（文档说这个过滤只对自动 payout 有效）。
2. 测试卡 `4000 0000 0000 0077` 能让资金直接进入可用余额，便于快速产生 payout。
3. 加拿大国内测试卡的手续费等于 `30 + round(2.9%)`；舍入方式不同就改合同公式，不加容差。
4. 余额不足时退款进入 `pending` 的行为在测试模式下可复现。
5. 有能让 payout 失败的加拿大测试银行账户号。
6. webhook 端点返回非 2xx 时的重试节奏（只用于报告，不写进业务逻辑）。
7. 测试模式的接口速率上限，决定流量生成器和演示页的限流值。
