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

两点要如实写进 README：

- 测试模式下结算单和 webhook 都来自 Stripe，两者的独立性比模拟器模式弱（真实对账也是如此，处理方的报表就是处理方的话）。对账准确率（误报、漏报）的评测只在模拟器模式做，那里有真值。
- fake Stripe 只实现我们用到的子集，行为以真实 Stripe 为准；两者的差异靠契约测试抓（第 6 节）。

## 2. 事件映射

webhook 端点只订阅下表的事件类型。

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

## 3. 出站调用

| 用途 | 接口 | 幂等与恢复 |
|---|---|---|
| 提交退款 | `POST /v1/refunds`（`charge`、`amount`、`metadata.ledgerlab_refund_id`） | `Idempotency-Key` = 退款号 `rfd_…`；响应里的 `status` 当作退款事实处理（第 2 节） |
| 查询退款 | 已知 `re_…` 时 `GET /v1/refunds/re_…`；不知道时 `GET /v1/refunds?charge=ch_…` **翻完全部分页**（直到 `has_more=false`），按 metadata 找 | 结果未知时先查再重发 |
| 批次行 | `GET /v1/balance_transactions?payout=po_…`（分页） | 只读 |
| 对账导入 | `GET /v1/balance_transactions?created[gte]&created[lt]`、`GET /v1/payouts?…` | 只读；按截止时点取数，原始结果存档并算哈希 |

**Stripe 的幂等键至少保留 24 小时，之后可能被清理**（Stripe idempotent requests 文档）。所以退款命令在 `UNKNOWN` 状态下的规则是：

1. **任何重发之前先查询**。距第一次发送（命令上不可变的 `first_sent_at`）超过 20 小时的命令，只有查询**完整成功**并确认 Stripe 上没有这笔退款时才允许重发。
2. “完整成功”指：按编号直接查到结果，或列表翻完全部分页且每一页都成功。任何超时、5xx、分页中断都不等于“不存在”，命令保持 `UNKNOWN`。
3. 重发只能由持有当前租约的 worker 做；租约过期后旧 worker 的回执不能覆盖新结果（[state-machines.md](state-machines.md) 第 6 节）。

否则幂等键过期后的重发可能变成第二笔退款（例如原退款在列表第二页，只查第一页就会以为不存在）。

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

## 8. 协议版本（P1 开工前固定）

- Stripe API 版本固定为 **`2026-09-30.endive`**（2026-10-21 核对的当前版本；命名规则是发布日期加代号）。fake Stripe 的事件和对象形状、录制夹具、webhook 端点的 API 版本都用它。
- P1b 自行实现 HMAC-SHA256 验签，不引入 `stripe-java`；P3 需要出站调用时再选用与该 API 版本对应的 SDK 并固定版本。升级 API 版本是一次单独的改动，要重录夹具并跑契约测试。
- 事件格式用 snapshot 事件（带完整对象），不用 thin 事件。
- 验签：用原始请求体按 Stripe 方案算 HMAC；时间窗口**两侧**都检查（`|now − t| ≤ 300 秒`）。SDK 的验签函数只拒绝过旧的时间戳，未来时间要我们自己补检查。
- P1b 配置为 `ledgerlab.stripe.webhook-secret`（`STRIPE_WEBHOOK_SECRET`，必填且以 `whsec_` 开头）和 `ledgerlab.stripe.api-key`（`STRIPE_API_KEY`，可空，非空须以 `sk_test_` 或 `rk_test_` 开头）；启动时校验且错误不包含密钥值。签名有效但 JSON 不合法或缺少 `id/type/api_version/livemode/created` 时返回 400、不入库。
- P1 的 fake Stripe 只需要 `charge.succeeded`；事件 JSON 按上述版本的官方对象定义写，P6 用真实录制夹具对照（第 6 节）。
