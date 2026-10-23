# LedgerLab fake Stripe（P2 T03）

独立 Spring Boot 4.1.1 / Java 25 Maven 项目，不是根项目子模块、不依赖主应用 Java 代码。fake Stripe 实现 charge、余额行、payout snapshot 事件与只读 Stripe REST；同进程的 `relay` 包提供 webhook 投递故障注入。退款执行留在 P3。

## 构建与启动

在仓库根目录运行，需要 Java 25、Docker（测试使用真实 PostgreSQL 18）：

```sh
./mvnw -B -f simulator/pom.xml verify
./mvnw -f simulator/pom.xml spotless:apply
```

PowerShell 用 `.\mvnw.cmd` 替换 `./mvnw`。从根目录调用 Wrapper 时，`-f` 仍加载根 `.mvn/jvm.config`；编译实际执行 Error Prone 和 NullAway，verify 检查 Spotless 及 JaCoCo 行覆盖率 ≥80%。测试复制根数据库初始化脚本，隔离测试只读取根迁移文件建出真实 `ledger.v_account`，不引用主应用类。

先按根 README 配置 PostgreSQL 并 `docker compose up -d`。初始化脚本创建 `simulator` 角色与四个空 schema。模拟器运行和 Flyway 均以 simulator 登录；历史表位于 sim_processor，不创建 schema。

环境变量（进程必须实际继承；Spring 不会自动读取根 `.env`）：

| 变量 | 必需 / 默认值 |
|---|---|
| `LEDGERLAB_SIMULATOR_PASSWORD` | 必需，与 PostgreSQL 初始化一致 |
| `SIM_STRIPE_WEBHOOK_SECRET` | 必需，与主应用 `STRIPE_WEBHOOK_SECRET` 相同 |
| `SIM_STRIPE_API_KEY` | 只读 REST 必需，仅接受 `sk_test_` / `rk_test_`；可用合成测试值，留空时所有 `/v1` 请求 401 |
| `SIM_STRIPE_AVAILABILITY_DELAY` | `PT0S`，非负 ISO-8601 Duration；配置键 `sim.stripe.availability-delay` |
| `SIM_DATABASE_URL` | `jdbc:postgresql://localhost:5432/ledgerlab` |
| `SIM_STRIPE_WEBHOOK_URL` | `http://localhost:8080/webhooks/stripe` |
| `SIM_RELAY_TARGET_URL` | `http://localhost:8080/webhooks/stripe`，relay 下游地址 |
| `SIM_PORT` | `8081` |
| `SIM_SEED` | `1`，编号随机序列种子 |
| `SIM_CLOCK_START` | 可选 ISO-8601 UTC 时刻；默认进程启动时刻 |

```sh
./mvnw -f simulator/pom.xml spring-boot:run
# 或先 verify 后运行独立 jar：
java -jar simulator/target/ledgerlab-simulator-0.0.1-SNAPSHOT.jar
```

编号同种子同序列，重放完整场景应使用空模拟器数据库；保留数据重新启动并创建新场景时使用不同种子，避免重用旧编号。模拟时钟冻结在起始值，通过 `POST /sim/clock/advance` 或场景代码的 `SimulationClock.advance(Duration)` 快进。用三天前的 `SIM_CLOCK_START` 可直接生成旧事件；签名始终使用独立现实 UTC 时钟。快速连续重投同事件时，签名秒数至少比上次大一秒；大量紧密重投可能超出主应用五分钟窗口，应按真实重试节奏调用。

## 控制接口

全部为模拟器接口，不是 Stripe REST API。返回 HTTP 200；结构错误返回 400；不存在的事件返回 404。

| 接口 | 输入 / 返回 |
|---|---|
| `POST /sim/charges` | 必填整数 `amount`，可选整数 `fee` 覆盖；返回 `{charge, event_id}` 并立即投递，同事务创建 balance transaction |
| `POST /sim/events/{id}/redeliver` | 无请求体；原文不变，用新签名时间投递；返回 `{event_id}` |
| `POST /sim/events/{id}/reissue` | 无请求体；同一 Charge/Payout 快照和事件类型、新事件号及 event created；返回 `{charge, event_id}` 或 `{payout, event_id}` |
| `GET /sim/events/{id}` | `{payload, deliveries}`；payload 为精确原文字符串，投递按 id 排序 |
| `POST /sim/payouts` | 可空或 `{}`；可选 `automatic`（默认 true）、`method`（standard/instant，默认 standard）、整数 `amount` 覆盖；返回 `{payout,event_id}` |
| `POST /sim/payouts/{id}/reconcile` | 无请求体；明细 completed，事件 payout.reconciliation_completed |
| `POST /sim/payouts/{id}/paid` | 无请求体；事件 payout.paid |
| `POST /sim/payouts/{id}/failed` | 可选 `failure_code`（默认 account_closed）；返还资金，事件 payout.failed |
| `POST /sim/payouts/{id}/faults` | 持久故障计划，格式见下；返回当前 payout |
| `GET /sim/clock` | `{"now":"2026-10-23T12:00:00Z"}` |
| `POST /sim/clock/advance` | `{"seconds":30}`，仅非负整数；返回推进后的 now，同步执行到期 relay 回调；倒退/溢出返回 400 |

控制接口供本地场景使用，无 Stripe 鉴权。新接口结构/数值错误返回 400，不存在的对象 404，不允许的状态转换 409。金额使用 long 整数分和精确运算；`amount/fee/net` 的绝对值小于 2^53。创建 charge 默认手续费 `30 + (amount*29+500)/1000`，charge 的 `balance_transaction` 字段指向对应 txn。charge 与 balance transaction、事件一起提交后才投递。原有拒绝类 charge 覆盖仍支持，用于生成主应用拒绝事件，不应混入正常结算场景。

标准 payout 归集 `available_on <= 模拟现在` 的全部尚未归集 CAD 行，净额 ≤0 返回 400。初始 pending/in_progress；arrival_date 默认为创建时刻加 24 小时，仅是模拟预计时间，不模拟银行工作日日历。手动/即时 payout 使用 not_applicable，不能 reconcile。相同状态转换重试返回原 event_id，不新增投递。paid 后允许 failed；failed 后再 paid 为 409。失败生成正向 payout_failure，原批次行不解绑；下一笔仅带返还行和自己的负 payout 行。amount 覆盖用于负 payout/金额不符故障，仍归集同一集合，借记采用覆盖值；故障后用空库新场景恢复正常余额语义。

### Stripe 风格只读接口与分页

版本固定 `2026-09-30.endive`，对象字段参照 [Payout](https://docs.stripe.com/api/payouts/object)、[Balance Transaction](https://docs.stripe.com/api/balance_transactions/object) 和 [分页对象](https://docs.stripe.com/api/balance_transactions/list)。

```sh
curl -H "Authorization: Bearer $SIM_STRIPE_API_KEY" http://localhost:8081/v1/payouts/po_REPLACE
curl -G -H "Authorization: Bearer $SIM_STRIPE_API_KEY" http://localhost:8081/v1/payouts \
  --data-urlencode 'created[gte]=1792713600' --data-urlencode 'created[lt]=1792800000' -d limit=10
curl -G -H "Authorization: Bearer $SIM_STRIPE_API_KEY" http://localhost:8081/v1/balance_transactions \
  -d payout=po_REPLACE -d limit=10 -d starting_after=txn_REPLACE
```

key 必须精确等于 `sim.stripe.api-key`；缺失/错误/live key 都 401，配置 live key 时启动拒绝且不回显值。列表为 `{object:"list",data:[...],has_more:boolean,url:"/v1/..."}`。created 为左闭右开范围，按 created DESC、id DESC；同秒编号排序为模拟器的确定性规则。limit 默认 10、范围 1～100，超限返回 400；starting_after 必须属于当前筛选结果，否则 400。持续用末行 id 翻页直到 has_more=false。明细包含 payout 自身的负行，主应用必须跳过；fake 在 in_progress 也允许检查明细，但适配器仍需等待 completed。模拟器只实现上述参数和接口子集。

### 持久故障注入

charge fee 覆盖、payout 参数覆盖和每次 faults 调用都追加到 `sim_processor.fault_plan`（target_id、完整请求、recorded_at）。失败的控制请求整体回滚。faults 可组合以下字段：

```json
{"duplicate_transaction":"txn_REPLACE","fail_page":2,"extra_line":{"type":"charge","amount":1000,"fee":0}}
```

- `duplicate_transaction`：必须是该 payout 已有 txn；响应中紧随原行复制一次，不重复落 balance transaction。该故障页故意比 limit 最多多一行，游标仍按未复制集合推进，避免相同 id 导致循环；传 null 关闭。
- `extra_line`：type 只能 charge/adjustment；amount 必填整数，fee 默认 0，source 可选（默认生成未知 ch_/adj_）。新增 txn 持久属于该 payout，原 payout 金额不变，能制造未知支付、不支持行和批次合计不符。
- `fail_page`：正整数 k 在第 k 页持续返回 500，0 关闭；页码按游标偏移/limit 计算，故障重试行为稳定，验证时保持 limit 不变。

`PayoutTest` 用真 PostgreSQL 验证三笔 charge 净额 18359、样例 H 返还 9680、25 行分页和中断、鉴权、故障记录、并发归集、时间推进与确定性编号。覆盖 T03 对 AC-5.1、5.7、5.10、5.13、5.17～5.19、AC-2.2、AC-4.13、AC-8.2 的模拟器支持，不代表主应用结算/对账 AC 已完成。

```sh
curl -H 'Content-Type: application/json' -d '{"amount":10000}' http://localhost:8081/sim/charges
curl -X POST http://localhost:8081/sim/events/evt_REPLACE/redeliver
curl -X POST http://localhost:8081/sim/events/evt_REPLACE/reissue
curl http://localhost:8081/sim/events/evt_REPLACE
```

默认 `currency=cad`、`captured=true`、`status=succeeded`、`amount_captured=amount`、`livemode=false`；均可覆盖来构造准入拒绝场景。金额仅接受 long 范围 JSON 整数，不进行主应用的业务范围检查。`livemode=true` 只是合成 JSON 字段，不调用真实 Stripe。

charge 与 event 原子提交后发送 HTTP；无自动重试。每次完成的投递记录目标 URL、签名秒数、现实记录时刻，以及 HTTP 状态码或网络错误类型。500 也记录状态码。连接超时 5 秒、请求超时 10 秒；进程在发送与日志落库之间崩溃可能留下无投递结果的事件，调用 redeliver 手动补投（P1 无可靠投递队列）。

协议固定 `2026-09-30.endive`；对象子集参考 [Event](https://docs.stripe.com/api/events/object) 和 [Charge](https://docs.stripe.com/api/charges/object)，签名按 [Stripe webhook 文档](https://docs.stripe.com/webhooks/signature) 对 UTF-8 原文字节计算 HMAC-SHA256。P6 再用脱敏真实夹具做版本契约校验。

fake Stripe 只实现所需子集，行为以真实 Stripe 为准。真实测试模式的结算单与 webhook 同源、证据独立性较弱；对账准确率只在有独立真值的模拟器模式评测，P1b 暂无结算及对账实现。

## Chaos relay

设置 `SIM_STRIPE_WEBHOOK_URL=http://localhost:8081/relay/stripe`，以及 `SIM_RELAY_TARGET_URL=http://localhost:8080/webhooks/stripe` 后启动模拟器，即为 fake Stripe → relay → 主应用。配置键分别为 `sim.stripe.webhook-url` 和 `sim.relay.target-url`；使用其他模拟器端口时同步修改前者。真实测试模式也可把 webhook 发到 `POST /relay/stripe`。

relay 保留原始请求体字节及 `Stripe-Signature`，不重新签名，也不因 JSON 无法解析或缺签名而拒收；主应用负责验签。上游返回 200 表示中间层已接收，下游 500 或网络错误只记日志，不改变上游响应，也不自动重试。PASS/DUPLICATE 同步完成转发后应答，DELAY/HOLD 入队后应答；连接超时 5 秒、单次请求超时 10 秒。

`PUT /sim/relay/plan` 原子替换计划，`GET /sim/relay/plan` 返回相同格式。规则按数组顺序取第一条匹配项；`event_id`、`event_type` 均可省略，同时填写时须都匹配，均不填则匹配所有请求。无匹配规则默认 PASS；初始计划为 `{"rules":[]}`。

```sh
curl -X PUT http://localhost:8081/sim/relay/plan \
  -H 'Content-Type: application/json' \
  -d '{"rules":[{"event_id":"evt_REPLACE","action":"HOLD_AND_REORDER","after_events":1},{"event_type":"charge.succeeded","action":"DUPLICATE","copies":3}]}'
curl http://localhost:8081/sim/relay/plan
```

| action | 参数 | 行为 |
|---|---|---|
| `PASS` | 无 | 原样转发一次 |
| `DUPLICATE` | `copies`，整数 ≥2 | 原样转发指定次数 |
| `DELAY` | `delay_seconds`，整数 0～240 | 到期转发；241 及以上返回 400，提示更长的延迟用 fake Stripe 的 redeliver 模拟 |
| `HOLD_AND_REORDER` | `after_events`，整数 ≥1 | 扣留到后续指定数量的事件完成转发；满 240 秒自动原样转发，日志动作 `HOLD_TIMEOUT_RELEASE` |
| `DROP` | 无 | 仅记接收日志，不转发 |

乱序计数按后续收到的事件计算；DUPLICATE 的多次转发整体只计一个事件，DROP 不计，延迟或扣留的后续事件在实际完成转发时才计数；下游失败也算完成一次尝试。提前释放后超时任务不再重复投递。修改计划不改变已经排队的请求。

延迟和扣留超时由 `SimulationClock` 的内存调度队列驱动；场景代码调用 `advance(Duration)` 或 HTTP `POST /sim/clock/advance`，到期回调在调用返回前执行，同一截止时间按入队顺序执行。模拟时间默认冻结，等待现实时间不会触发。快进时不得同时从多个场景线程推进时钟；签名仍用现实时间，真实运行场景需及时推进模拟时钟，避免原签名过期。

`sim_chaos.relay_log` 每次收到与每次转发各一行，用 `receipt_id` 关联同一次接收（相同事件号的不同接收互不混淆）。`attempt_no=0` 表示收到，1 起表示转发；包含事件号（无法读取则 NULL）、动作、状态码或网络错误、模拟时刻和原始请求体 SHA-256。计划和待发请求只在内存中，重启后恢复默认 PASS、待发请求丢失，日志保留；P1b 不提供可靠投递队列。

`RelayTest` 以真实 PostgreSQL 和 JDK HttpServer 验证本任务场景，为 AC-2.1（字节/签名）、AC-2.2（短延迟/乱序）、AC-2.3（重复）、AC-8.2（fake Stripe 协议）提供故障注入能力验证；不替代主应用对应的账本验收测试。
