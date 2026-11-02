# T02 chaos relay 出站代理、统计与录制

## 背景
stripe-integration.md 第 9.4、9.5 节；第 1 节“出站故障注入”；simulator/README 的 chaos relay 一节；`simulator/src/main/java/dev/ledgerlab/sim/relay/`。AC-8.6 的“丢掉 `POST /v1/refunds` 的响应”靠它实现。

## 范围
1. simulator：`ANY /relay/stripe-api/**` 代理，按 9.4 转发到 `sim.relay.upstream-api-url`（默认 `https://api.stripe.com`）。只接受 https，或 http 且主机是 loopback（测试用）；其他启动失败。转发头只限 9.4 列出的几个；`Authorization` 是 live 前缀直接 403 不转发；不跟随重定向；连接超时 5s、读超时 60s（可配置）。
2. relay 计划扩展出站规则（`direction":"OUTBOUND"`、`method`、`path_prefix`、`action` ∈ PASS/DROP_RESPONSE/FAIL_BEFORE_SEND、`times` ≥1）。入站规则格式和行为完全不变；混合计划按方向分别取第一条匹配规则。`times` 计数和现有计划一样只在内存，`PUT /sim/relay/plan` 替换时重置。
3. DROP_RESPONSE：转发并读完上游响应后，给调用方 504（正文 `{"error":"relay dropped upstream response"}`），日志记上游真实状态。FAIL_BEFORE_SEND：不转发，503。
4. 新迁移（simulator 的下一个版本号）：`sim_chaos.api_exchange`（只追加：id、received_at、method、path、query 参数名列表、upstream_status、action、duration_ms、request_sha256、response_sha256、request_body、response_body 可空）和 `sim_chaos.inbound_recording`（只追加：relay 收到的 webhook 原文、event_id、event_type、received_at、sha256）。两表只给 simulator 角色 INSERT/SELECT，禁止 UPDATE/DELETE（照现有 sim 表的授权写法）。正文只在 `sim.relay.record-bodies=true` 时保存（默认 false）；**任何请求头都不存**。
5. `GET /sim/relay/stats?since=<ISO-8601>` 返回 `{inbound:{PASS:n,DUPLICATE:n,…}, outbound:{PASS:n,DROP_RESPONSE:n,FAIL_BEFORE_SEND:n}}`，从日志表统计。
6. 主应用：确认 `ledgerlab.stripe.api-base-url` 带路径前缀（如 `http://simulator:8081/relay/stripe-api`）时 `RestStripeReader` 和 `StripeRefundAdapter` 请求的路径正确（`/relay/stripe-api/v1/...`）；不对就做最小修复。加一个 loopback 测试证明。
7. simulator/README 增加“出站代理”一节；stripe-integration 第 9.4 节补实现备注。

## 测试（不联网，JDK HttpServer 当上游）
- 转发保真：方法、路径、查询串、form 正文、4 个头原样到上游；响应状态/正文/Request-Id 原样回来；上游 3xx 不跟随。
- `ac_8_6_dropResponseExecutesUpstreamOnceAndReturns504`：DROP_RESPONSE 时上游恰好收到一次请求，调用方 504，`times` 用完后下一次 PASS。
- live 前缀 403 且上游零请求；数据库里搜不到 Authorization 值（断言表中任何文本列都不含测试用假密钥）。
- record-bodies 开/关两种；api_exchange、inbound_recording 用 simulator 角色 UPDATE/DELETE 被数据库拒绝。
- 入站既有 relay 测试全部照过；stats 计数正确。
- 主应用：带路径前缀的 base URL 测试（reader 和 refund adapter 各一）。

## 可改范围
`simulator/**`（relay 包、新迁移、配置、README、测试）、`src/main/java/dev/ledgerlab/ingestion/internal/**`（只限第 6 条的最小修复）及其测试、`docs/design/stripe-integration.md`。

## 完成标准
按 `_common.md` 第 5 条：simulator verify 全绿；主应用 test-compile＋spotless:check＋相关测试类＋ModularityTests。本地提交（`Task: T02`）。
