# T03 simulator 沙箱桥：流量生成器、银行接真实 payout、时钟跟随现实

## 背景
stripe-integration.md 第 9.1（运行编号、时钟跟随现实）、9.3 节；T01 的实测结论（`docs/handoff/p6-probe.md`：哪张测试卡可用、费率）；simulator 的 `bank.IncomingTransfers`、`processor.SimulationClock`。

## 范围
1. simulator 新包 `dev.ledgerlab.sim.sandbox`，只在 Spring profile `sandbox` 下装配；新增 `application-sandbox.yaml`。配置：`sim.sandbox.stripe-api-key`（环境变量 `STRIPE_API_KEY`，启动时只接受 `rk_test_`/`sk_test_`，live 或其他前缀拒绝启动，错误信息不含值）、`sim.sandbox.stripe-base-url`（默认 `http://localhost:8081/relay/stripe-api`，即经过 T02 的出站代理）、`sim.sandbox.run-id`（必填，`sbx_` 开头）、`sim.sandbox.window-start`（ISO-8601，必填）。HTTP 用现有 RestClient/JDK 客户端，不加依赖。
2. 流量生成器 `POST /sim/sandbox/payments {"count":1..50,"card":"<pm_card_…>","seed":n}`：按 9.3 生成金额和请求；`Idempotency-Key = <run_id>:pay:<card>:<seed>:<序号>`。卡只接受白名单（`pm_card_bypassPending` 和 T01 确认的加拿大卡）。每笔结果（序号、金额、pi_、ch_、状态、HTTP 状态、错误码）追加进新表；返回汇总。部分失败不回滚已成功的。
3. `POST /sim/sandbox/bank/sync`：按 9.3 列出窗口内 paid payout（`GET /v1/payouts?status=paid&created[gte]=<window-start>&limit=100` 翻完 `has_more`；任何一页失败则整个同步不做任何入账，返回 502 和已翻页数），合格的调 `IncomingTransfers.receive(po_, amount)`，不合格的记原因。返回 `{seen, received, skipped:[{payout, reason}]}`。每次同步结果追加进新表。
4. `GET /sim/sandbox/payments` 返回本 run 全部生成记录（按序号）；`GET /sim/sandbox/summary`：生成器各卡笔数/金额合计、成功失败数、银行同步收到的 payout 数——给运行报告用。
5. 时钟跟随现实：`sim.clock.follow-wall=true`（sandbox profile 里设为 true，默认 false）时，每秒把 `SimulationClock` 推进到现实 UTC（只进不退，现实时间早于模拟时间时什么也不做），推进后照常执行到期 relay 回调和银行计划。
6. 新迁移：`sim_sandbox` schema 和上面两张只追加表（simulator 角色 INSERT/SELECT，禁止 UPDATE/DELETE）。主应用不读这个 schema。
7. simulator/README 增加“沙箱模式”一节；stripe-integration 第 9.3 节补实现备注。

## 测试（不联网，JDK HttpServer 冒充 Stripe）
- 生成器：同种子同金额序列；请求 form 字段与 `Idempotency-Key` 正确；重放同一请求不新增表行以外的副作用（上游按幂等键返回原对象时不重复计数）；非白名单卡 400；count 越界 400。
- bank sync：两页 payout 都翻完；合格的进入银行转账且重复 sync 不重复入账（查模拟银行转账数）；第二页失败时零入账；手动/即时/非 CAD/零金额的跳过。
- 启动校验：live 前缀、缺 run-id 时 sandbox profile 启动失败；非 sandbox profile 下这些 bean 不存在、`/sim/sandbox/**` 404。
- follow-wall：用可控的“现实时钟”注入，推进只进不退；默认 false 时冻结行为不变（既有时钟测试照过）。
- 新表 UPDATE/DELETE 被数据库拒绝。

## 可改范围
`simulator/**`、`docs/design/stripe-integration.md`。

## 完成标准
按 `_common.md` 第 5 条（simulator verify 全绿）。本地提交（`Task: T03`）。本任务不连真实 Stripe。
