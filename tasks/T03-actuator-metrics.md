# P10-T03 Actuator 管理端口与业务指标（AC-12.4、AC-12.5）

## 背景
读 `docs/design/observability.md` 第 2、3 节（照做）和 ADR-0021 第 2 条。**用户已批准**新增 `spring-boot-starter-actuator` 和 `micrometer-registry-prometheus`（见本任务末尾“用户批准”）；版本跟随 Spring Boot BOM，**不写版本号**，不加别的依赖。用 Context7 查 Spring Boot 4.1 的 Actuator（管理端口、端点暴露、Prometheus）和 Micrometer（`Gauge`、`MultiGauge`、`Counter`）文档，不凭记忆。

## 要做的
1. `pom.xml` 加两个依赖。
2. 管理端口：`management.server.port: ${LEDGERLAB_MANAGEMENT_PORT:8079}`、`management.server.address: 127.0.0.1`、只暴露 `health,prometheus`、`health` 不显示细节。测试环境（`src/test/resources` 或测试基类）把管理端口设为 `0` 或与主端口相同，以免并行测试抢端口；所有已有 `@SpringBootTest` 不能因此变红。演示 profile 的公开白名单（AC-9.3、`DemoPathWhitelist`）不改。
3. 业务 meter（放在拥有数据的模块：inbox→`ingestion`，出站→`outbound`，差异→`reconciliation`），各自注册到 `MeterRegistry`：
   - gauge 每次抓取时经 `readonly` 数据源查库（看 `investigation` 模块的 `ReadonlyConfiguration` 怎么拿只读数据源；如果别的模块拿不到、或拿到会违反 Modulith 边界，**停下来问**，写出你倾向的做法）。查询异常时返回 `NaN` 并记一行 WARN（不带 SQL 参数），不让抓取失败。
   - `readonly` 角色要能读到用到的列（ADR-0014）；如果某列没授权，**停下来问**（需要迁移就是越界）。
   - 年龄类 gauge 用注入的 `Clock` 算。
   - `ledgerlab.outbound.attempts` 是 counter，在 `OutboundWorker` 每次调用结束时加（`kind`、`outcome`、`retry`），不查库。
   - 多标签 gauge（按 `status`、`kind`、`category`）用 `MultiGauge` 或等价做法，每次抓取刷新；标签值只来自封闭枚举，**不许**用编号作标签。
4. 已有的测试支持类（`testsupport/`）如需暴露管理端口的地址，加一个小助手。

## 测试（新增，方法名带 AC 编号）
- AC-12.4：主端口 `/actuator/health`、`/actuator/prometheus` 404；管理端口 `/actuator/health` 200 且响应体只有 `status`；`/actuator/env`、`/actuator/heapdump`、`/actuator/loggers` 404；demo profile 下公开白名单测试（已有）继续通过。
- AC-12.5：Testcontainers 真库，造出已知数据（直接用 `app` 角色经已有入口或测试夹具写入：几条 `RECEIVED` 到期／未到期、`PARKED` 事件；一条重试过两次的出站命令和一条达到次数上限的；几条不同类别的 `OPEN` 差异和一条 `RESOLVED` 的），固定时钟，抓 `/actuator/prometheus`，解析文本，断言每个指标的值和标签精确等于预期；时钟前进后年龄 gauge 跟着变；标签值里没有 `evt_`、`cmd_`、`iss_`、`rr_`、`po_` 前缀。
- AC-12.5（数据库不可用）：让只读查询失败（例如测试里停掉/断开数据源，或注入一个会抛异常的查询），抓取仍 200，对应 gauge 为 `NaN`。做不到可靠复现就写实现备注说明替代测法。
- `ModularityTests` 通过。

## 可改范围
- `pom.xml`（只加上述两个依赖）
- `src/main/java/dev/ledgerlab/**`（meter 注册、配置；不改业务语义）
- `src/main/resources/application*.yaml`
- `src/test/**`
- `docs/design/observability.md`（实现备注）
- **不许碰**：Flyway 迁移、`agent/`、`frontend/`、`simulator/`、`deploy/`

## 完成标准
新测试和受影响的已有测试全绿（**至少**跑 `ModularityTests`、演示白名单测试、新测试类、以及任何因管理端口配置受影响的测试；如果不确定影响面，跑 `./mvnw -B test` 全部单元＋集成测试，但不要跑 `verify`）；`spotless:check` 通过；提交带 `Task: P10-T03`。

## 用户批准
（执行者在用户批准后填写原话和日期；没有这一段不要开始。）
