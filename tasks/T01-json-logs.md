# P10-T01 结构化 JSON 日志、请求号与业务编号（AC-12.1～12.3）

## 背景
读 `docs/design/observability.md` 第 1 节（全文照做）和 ADR-0021 第 1 条。现在全仓只有 8 条日志（`OutboundWorker`、`SettlementPolling`、`DemoActions`、`DemoArchive`）。

## 要做的
1. **开关**：`application.yaml` 里 `logging.structured.format.console: ${LEDGERLAB_LOG_FORMAT:}`。先验证空值时 Spring Boot 4.1 能正常启动并保持人读格式（用 Context7 查 Spring Boot 文档，再实测）；不行就改为 profile `json-logs`，写实现备注。`application-demo.yaml` 和 `compose.demo.yaml` 里 app 的环境设为 `logstash`。
2. **上下文工具**：在 `shared` 模块加一个小工具（例如 `LogContext`），`try (var ctx = LogContext.with("eventId", id)) {...}` 风格，关闭时恢复进入前的 MDC 值（嵌套安全）。键名用设计 1.2 节的固定拼写，最好集中成常量。
3. **请求号过滤器**：一个 `OncePerRequestFilter`（放哪个模块自己定，必须过 `ModularityTests`；注意与已有 `DemoGateFilter` 的顺序，请求号要最先放入）：接受合格的 `X-Request-Id`，否则生成 `req_`＋ULID（独立随机来源，**不用 `IdGenerator`**）；响应头回写；请求结束记一行 `http.request`（路由模板从 `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` 取，取不到写 `UNMATCHED`；不记 `/actuator/**`）；`finally` 清 MDC。
4. **业务事件**：按设计 1.3 节的表在对应位置加日志并放入编号：webhook 接收与拒收（`ingestion`）、inbox 处理与失败（`InboxWorker`）、出站每次调用与达上限（`OutboundWorker`，把现有那条 WARN 改成 `outbound.exhausted`）、对账运行结束、差异开／重开／转状态（`reconciliation` 的 `ReconRuns`、`IssueStore`）、payout 处理里的 `batchId`。`event.action` 等附加字段用 SLF4J fluent API 的 `addKeyValue`（logstash 格式会输出成顶层字段），业务编号用 MDC。日志调用不得在事务里抛异常影响业务。
5. **不许出现**：设计 1.4 节。

## 测试（新增，方法名带 AC 编号）
- AC-12.1：开 `logstash` 时捕获的控制台输出每一行都能被 Jackson 解析为对象且含 `@timestamp`、`level`、`logger_name`、`message`；不开时输出不是 JSON（一个用 `OutputCaptureExtension` 的 Spring Boot 测试即可，开关用 `@SpringBootTest(properties=...)`；如果结构化格式必须在日志系统初始化前设定、测试里切换困难，就用 `SpringApplicationBuilder` 在测试里单独起一次应用，或用子进程，写实现备注）。
- AC-12.2：Testcontainers 真库、JSON 日志开启：(a) 一次 HTTP 请求：响应头 `X-Request-Id` 与该请求的 `http.request` 行的 `requestId` 相同；带合格头时原样沿用；带坏头（含空格或超长）时被替换为 `req_` 开头；(b) 一条经真实 webhook 入口（验签）进来的事件被 inbox worker 处理后，`webhook.received` 和 `inbox.processed` 行都带同一个 `eventId`；(c) 一条出站命令执行后 `outbound.attempt` 行带 `commandId`；(d) 一次对账开出差异后 `recon.issue.opened` 行带 `issueId` 和 `runId`，主体是批次时带 `batchId`；(e) 同一线程处理完后下一行不残留上一条的编号（`LogContext` 单元测试覆盖嵌套和恢复）。可以复用已有测试的场景搭建（看 `src/test/java/dev/ledgerlab/ingestion/internal/WebhookIntakeTest.java`、`InboxWorkerTest`、对账相关测试和 `testsupport/`）。
- AC-12.3：把 AC-12.2 场景的全部捕获日志拼起来，断言不含 webhook 原始请求体的特征片段、`Stripe-Signature`、`whsec_`／`sk_`／`rk_`／`pk_`（拼接构造）、测试里用的数据库密码、测试里造的银行 memo 和操作人备注原文。
- AC-4.13 的重建确定性测试继续通过（跑一下现有的重建测试类）。

## 可改范围
- `src/main/java/dev/ledgerlab/**`（只加日志、MDC、过滤器和 `shared` 里的工具；不改业务逻辑和 SQL 语义）
- `src/main/resources/application.yaml`、`application-demo.yaml`
- `compose.demo.yaml`（只加 app 的 `LEDGERLAB_LOG_FORMAT`）
- `src/test/java/dev/ledgerlab/**`（新增测试；给既有测试补夹具）
- `docs/design/observability.md`（实现备注）
- **不许碰**：Flyway 迁移、`pom.xml`、`agent/`、`frontend/`、`simulator/`、`deploy/`

## 完成标准
上面的测试全绿；`ModularityTests`、相关已有测试类（webhook、inbox、出站、对账、重建）全绿；`spotless:check` 通过；提交带 `Task: P10-T01`。
