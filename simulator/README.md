# LedgerLab fake Stripe 与模拟银行（P2 T07）

独立 Spring Boot 4.1.1 / Java 25 Maven 项目，不是根项目子模块、不依赖主应用 Java 代码。fake Stripe 实现 charge、余额行、payout snapshot 事件与只读 Stripe REST；同进程的 `relay` 包提供 webhook 投递故障注入，`bank` 包维护独立的转账计划、银行执行记录与 CSV 流水。退款执行留在 P3。

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
| `POST /sim/clock/advance` | `{"seconds":30}`，仅非负整数；返回推进后的 now，同步执行到期 relay 回调与银行计划；倒退/溢出返回 400 |

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

## 退款（P3 T06）

仅合成测试数据；不连接真实 Stripe。退款 REST 必须带 `Authorization: Bearer <sim.stripe.api-key>` 和 `Stripe-Version: 2026-09-30.endive`；创建另带 `Idempotency-Key`。例如：

```http
POST /v1/refunds
Content-Type: application/x-www-form-urlencoded
Idempotency-Key: rfd_demo

charge=ch_REPLACE&amount=2000&metadata%5Bledgerlab_refund_id%5D=rfd_demo
```

`GET /v1/refunds/re_REPLACE` 读取当前对象；`GET /v1/refunds?charge=ch_REPLACE&limit=10&starting_after=re_CURSOR` 读取创建倒序页，必须继续翻到 `has_more=false`。同一秒按实际创建顺序反排。只支持 CAD 已请款测试 charge、显式正整数 amount 与上述 metadata 字段；完整子集及 Stripe 差异见 [设计第 6 节](../docs/design/stripe-integration.md#6-契约测试fake-stripe-和真实-stripe-不能漂移)。

未打出且已可用的净额够时退款立即 succeeded，新增 −amount 的余额行；否则 pending，不扣余额。推进模拟时钟、新 charge 提交、`POST /sim/refunds/process` 都检查 pending；按创建顺序检查、跳过余额不足项。该控制接口返回生成的事件数 `events`（suppress_events 时可为零）。执行后的负向退款行随下次 payout 归集。商户余额和主应用 FIFO 队列不在模拟器内建模。

故障设置接口为 `POST /sim/refunds/faults`，JSON 示例：

```json
{"charge":"ch_REPLACE","timeout_after_create":1,"force_pending":true}
```

省略 charge 表示全局。每次替换该范围计划；指定 charge 的字段覆盖全局同名字段，其他字段继承全局。`{}` 清除全局计划；`{"charge":"ch_REPLACE"}` 清除该 charge 覆盖。

| 字段 | 语义 |
|---|---|
| timeout_before_create | 前 n 次请求返回 504，不建退款、不缓存响应 |
| timeout_after_create | 前 n 次请求保存退款并投递事件后挂起 HTTP；客户端应设置短于 30 秒的读取超时 |
| fail_next | 下一笔创建时先 pending 再 failed，值为 failure_reason；一次性消耗，不写退款余额行 |
| force_pending | true 时创建和自动扫描均保持 pending；false 可覆盖全局 true |
| suppress_events | true 时不保存、不投递任何退款事件；可用于只靠响应完成退款 |
| event_before_response | true 保证投递尝试完成才返回；模拟器默认也沿用同步投递。relay DROP/DELAY/HOLD 仍按自己的计划处理 |
| list_page_timeout | 正整数 k 表示列表第 k 页返回 504；0 关闭。页号按当前游标偏移/limit 推导，重试同页稳定失败 |

计数和计划持久化，设置记录追加存档。故障注入不使用 sleep；创建后挂起释放 servlet 线程，30 秒 transport timeout 仅回收资源并返回 504。

`POST /sim/refunds/re_REPLACE/succeed` 手动推进 pending（忽略 force_pending，但仍检查可用余额）；`POST /sim/refunds/re_REPLACE/fail {"reason":"declined"}` 确认失败并释放 charge 可退额度。相同终态重试不重复产生余额行或事件；终态冲突返回 409。failed/canceled 不占可退额度；canceled 只保留为协议状态，本任务没有取消控制接口。

`POST /sim/idempotency/purge {"before":"2026-10-25T12:00:00Z"}` 只清理模拟时间已满 24 小时、且创建早于 before 的键；省略 before 使用模拟 now。缓存保留首次响应，所以重放可能返回旧 pending 快照；查当前状态应 GET。清理后重用同键会新建退款，metadata 不去重，这是 AC-8.7 要重现的条件。

场景导出运行器新增以下最小动作（仍在独立事务回滚，不投递外部 HTTP）：

```json
[
  {"action":"refund_fault","request":{"charge":"$P1","force_pending":true}},
  {"action":"advance_clock","seconds":86401},
  {"action":"process_refunds"}
]
```

`advance_clock` 也接受 ISO `to`，原有 `advance` 动作保留；refund_fault 支持既有别名替换。退款业务场景及回放编排留给 T13。

## 模拟银行

银行只通过 `IncomingTransfers.receive(payoutRef, amount)` 接收通知，不依赖 processor 包，也不查询 `sim_processor`。fake Stripe 首次变成 paid 时，在保存 paid 状态、事件的同一事务内通知银行；重复 paid、事件 redeliver/reissue 都不会增加转账。仅标准自动 CAD 正 payout 产生银行收入，手动、即时、零或负 payout 不通知。paid 后 failed 不自动撤销银行已接收的转账；这两方的独立事实可以产生冲突，退回流程不在 T07。

单账户 `SIM-CAD-001` 初始余额为 0。银行接收通知时冻结当时的计划，默认在模拟多伦多日期后的下一个工作日执行一笔 BOOKED CREDIT，reference 为 payout 号。银行的 `calendar/business-holidays.csv` 是主应用同名文件的**独立副本**，不在运行时引用主应用资源；维护假日时必须同步两份 CSV 与覆盖年份。目前支持 2026–2027，排除周末、11-11 等非处理日，超出覆盖范围拒绝。超过单行上限 99999999 分的汇总转账会按上限分成多行，不生成非法金额行；因此这类大额汇总不属于首版单行精确匹配的正常样例。

银行计划、收到的转账、执行记录与文件版本均持久化。`POST /sim/clock/advance` 会执行到期计划；银行查询/导出也会补执行到期工作，支持重启后恢复。直接用 Java `SimulationClock.advance` 的场景随后调用 `Bank.advance()` 或银行查询以推进银行。历史日期文件由银行实际执行记录生成，读取文件不查询处理方。银行各操作以账户行锁串行化；失败整体回滚，重试不会重复入账。`GET /sim/bank/transactions` 返回按 booking_date、bank_txn_id 排序的当前实际流水数组；尚未到期的转账不在数组内。

### 银行故障计划

`PUT /sim/bank/plan` 设置默认规则或一笔 payout 的规则；返回规范化后的计划，错误类型、未知字段或非法参数返回 400：

```json
{"payout_ref":"po_REPLACE","scenario":"short-deposit","fault":"SHORT","parameter":1,"memo":"synthetic bank deposit"}
```

`payout_ref` 默认 `*`，精确 payout 规则优先；`scenario` 默认 `manual`，`memo` 默认 `Simulated bank transfer`。`{}` 设置默认 NONE；指定 payout 的 NONE 会屏蔽默认故障。计划只影响之后收到的转账，单笔只选一种故障，不修改既有转账计划。parameter 必须为 JSON 整数。

| fault | parameter | 实际执行 | 真值预期类别 |
|---|---|---|---|
| NONE | 0（默认） | 正常入账 | 不记故障 |
| SHORT | 正整数分，小于本次金额 | 少到账 parameter 分 | DEPOSIT_AMOUNT_MISMATCH |
| DUPLICATE | 0 | 多一笔同金额、同 reference、不同 bank_txn_id | UNMATCHED_BANK_LINE |
| SPLIT | 0 | 拆为 `amount / 2` 与剩余部分，两个不同编号、同 reference；金额至少 2 分 | DEPOSIT_AMOUNT_MISMATCH |
| DELAY_DAYS | 1～730 | 正常入账日再延迟 n 个工作日，仍须在日历覆盖内 | TIMING_DIFFERENCE；超期后 DEPOSIT_NOT_RECEIVED |
| PENDING_FIRST | 0 | 原定工作日出现 PENDING，下一日历日同编号变 BOOKED | TIMING_DIFFERENCE |
| FOREIGN_CURRENCY | 0 | 正常行币种标签改 USD，金额数值不换算 | UNMATCHED_BANK_LINE |
| UNKNOWN_CREDIT | 1～99999999，默认 100 | 正常入账之外再加该金额来款，reference 为 `unknown_<payout号>` | UNMATCHED_BANK_LINE |

每次实际使用非 NONE 计划接收转账时，原子追加 `sim_truth.injected_fault`（场景、payout 号、故障、预期差异类别）；重复通知不重复记真值。设置但未使用的计划不算已注入故障。真值只描述本次注入，不从主应用账本或对账结果倒推；T12 再按场景时点处理延迟升级等派生发现。

### 银行流水 CSV

规范与 [contracts 第 4.1 节](../docs/design/contracts.md#41-银行-csv-文件格式p2-t07t08-解析契约) 一致，T08 按此解析：

```text
GET /sim/bank/statements/2026-11-09
Content-Type: text/csv;charset=UTF-8

H,BANK_STATEMENT,SIM-CAD-001,2026-11-09,2026-11-09,1,CAD
D,bt_po_example_1,CREDIT,9680,CAD,BOOKED,po_example,2026-11-09,2026-11-09,Simulated bank transfer
T,1,9680,0,0,9680
```

- UTF-8 无 BOM；实际记录分隔符和文件结尾均为 CRLF；无列名行或空白记录。包含逗号、双引号、CR/LF 的文本（包括 memo）用双引号包围，内部双引号加倍。memo 可跨物理行，行数按 D 记录数计算。
- 头 7 字段：`H,BANK_STATEMENT,<account>,<period_start>,<period_end>,<version>,CAD`。日报两端日期都为请求日期（含当天），只允许过去或模拟当天，多伦多日期、ISO `YYYY-MM-DD`。
- 明细 10 字段：`D,<bank_txn_id>,<CREDIT|DEBIT>,<amount>,<currency>,<BOOKED|PENDING>,<reference>,<booking_date>,<value_date>,<memo>`。金额为正整数分，方向单列。正常 CAD；USD 是故意注入的错误标签。
- 尾 6 字段：`T,<line_count>,<sum_credit>,<sum_debit>,<opening_balance>,<closing_balance>`。line_count 含 PENDING，收支与余额只计 BOOKED；`opening + credit - debit = closing`。FOREIGN_CURRENCY 的数值仍计入尾合计，不做汇率换算。期初来自此前所有 BOOKED 净额；无流水时 H/T 仍齐全，计数、收支 0，余额结转。
- PENDING_FIRST 首日在文件里出现时 booking_date/value_date 已固定为下一日历日；第二日除了状态变 BOOKED，其他字段不变，同一 bank_txn_id。两日文件各自从 v1 开始，历史文件重导仍保持原日状态。只有 BOOKED 证据参与主应用匹配和记账。
- 文件版本按 `(账户, 报表日期)` 独立计数，从 1 开始；相同规范内容重导返回相同字节，内容改变才加 1，所有干净版本存档，重启不丢失。流水证据版本则按 bank_txn_id 前进，见 contracts；不能把它与文件版本混为一谈。
- `?corrupt=trailer_count|trailer_sum|balance` 分别将尾行数、入账合计、期末余额加 1；不修改银行实际记录或已存干净版本。每次损坏响应都会追加真值（对象为账户、日期、版本）。未知 corrupt 返回 400。

`BankTest` 和 `PayoutTest` 通过真实 PostgreSQL 验证正常与故障执行、周末/假日/DST、多伦多归日、余额、PENDING 跨日、CSV 转义、持久版本、坏文件、并发幂等、回滚和恢复；以 app/readonly/migrator 直连验证新表及序列不可见，并扫描 bank 编译产物禁止 processor/主应用依赖。支持 AC-5.4～5.6、5.10～5.13、5.17、5.20 的模拟器场景，主应用导入与记账验收仍由 T08 及后续任务完成。
