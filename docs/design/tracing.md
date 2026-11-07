# 分布式追踪方案（P11，只写方案，不实现）

2026-11-07。对应 AC-13.7、[ADR-0022](../adr/0022-alert-delivery-and-tracing-plan.md) 第 4 条。P10 用 `requestId` 和业务编号把日志串起来（[observability.md](observability.md) 第 1 节）；本文件回答“如果要换成标准的分布式追踪，要做什么、花什么、值不值”。**P11 不实现**，是否做、何时做由用户决定。

## 1. 要解决的问题
现在能按 `eventId` 找到一条 webhook 的所有日志行，但看不到：一次请求或一条事件在哪一步花了多少时间；webhook 收进 inbox 之后，worker 在哪一次抢占里处理了它；它触发的出站命令（退款、提现）的每次调用和它是什么关系。这三条都跨了**异步边界**（先落库，另一个线程再取），日志里的 `requestId` 到不了下游。追踪要把这条链连成一棵树：

```
POST /webhooks/stripe ─ 验签 ─ 落 inbox            （请求线程，span A）
        ┆（库里的 inbox 行）
inbox worker 抢占 ─ 业务处理 ─ 记账 ─ 建出站命令   （worker 线程，span B，链接到 A）
        ┆（库里的出站命令行）
outbound worker ─ 调处理方／模拟银行（HTTP）       （worker 线程，span C，链接到 B；对方能续上则继续）
```

## 2. 用什么
| 组件 | 选择 | 理由 |
|---|---|---|
| API | Micrometer Tracing（`micrometer-tracing-bridge-otel`） | Spring Boot 4 官方支持；P10 已用 Micrometer，Observation API 同时出指标和 span，不用两套埋点 |
| 导出 | OpenTelemetry OTLP（`opentelemetry-exporter-otlp`） | 标准协议，换后端不改代码 |
| 传播格式 | W3C `traceparent`／`tracestate` | 标准；Stripe 不会回传，但模拟银行和调查服务可以 |
| 后端（本地） | Jaeger all-in-one 或 Grafana Tempo，固定版本镜像，放进 `observability` profile | 都是**新基础设施**；Jaeger 单容器最简单，Tempo 要配 Grafana 才好看 |
| 采样 | 演示站 100%（量很小）；部署时可降到 10%，错误全采 | 单机单实例，存储不是问题 |

**新依赖清单（铁律 5，需要用户批准）**：Maven 两个（`micrometer-tracing-bridge-otel`、`opentelemetry-exporter-otlp`，版本跟 BOM）；镜像一个（`jaegertracing/all-in-one` 或 `grafana/tempo`＋`grafana/grafana`）。Python 侧（调查服务、Agent）按“只用标准库”的规矩**不加** OpenTelemetry SDK，只手工读写 `traceparent` 头并把 trace id 写进日志，不导出 span。

## 3. 传播点
1. **HTTP 入站**：Spring 自动为每个请求建 span。`RequestLogFilter` 保留 `X-Request-Id` 头（兼容已有测试和前端），但 MDC 里加 `traceId`、`spanId`；`requestId` 不再自己生成，改为等于 trace id（或保留独立生成，二选一，见第 6 节）。
2. **webhook → inbox（异步边界 ①）**：落 inbox 时把当前 `traceparent` 写进 inbox 行；worker 抢占时读出来，新建一个 span，用 **span link**（不是父子）指向入站 span。用 link 而不是父子，是因为一条事件可能被重试很多次、隔几小时才处理，挂成子 span 会让入站请求的 trace 无限拉长。
3. **inbox → 出站命令（异步边界 ②）**：建出站命令时写入当前 `traceparent`；outbound worker 每次尝试建 span，link 到建命令时的 span。每次尝试的 HTTP 调用是它的子 span，`traceparent` 头发给模拟银行（它能续上）和 Stripe（会被忽略）。
4. **对账运行、老化、结算轮询**：定时任务各建根 span，`runId` 作为 span 属性。
5. **前端 → 主应用 → 调查服务**：调查服务读入站的 `traceparent`，转发给它调用的主应用只读接口，日志里带 `traceId`。

## 4. 需要的迁移
异步边界要在库里存 trace 上下文：
- `io.inbox_event` 加 `trace_parent text NULL`（`app` 可写入，不进任何约束和唯一键）；
- `io.outbound_command` 加 `trace_parent text NULL`；
- 两列都**不**授给 `readonly`（不是业务数据，也不该进对账和调查工具的视野），`core.v_inbox_backlog` 不变。
按 skill `flyway-migration` 新增一个迁移，三个角色的授权和权限测试照旧。

## 5. 与确定性重建的关系（AC-4.13）
- trace id 和 span id 由 OpenTelemetry 的随机来源生成，**不得**走业务 `IdGenerator`（否则带种子的编号序列会变，和 `requestId` 的规矩一样）。
- `trace_parent` 列在重建时为空或任意值，重建的比较必须**排除**这两列；现在的比较按业务表和业务列做，新列要显式加进排除清单并有测试。
- 场景输入日志不记录 `traceparent`。

## 6. 待用户决定的点
1. 做不做。单体单实例下，P10 的日志关联已经能回答“这条事件发生了什么”；追踪多给的是**耗时分解**和**跨异步边界的一棵树**，面试展示价值大于运维价值。
2. 后端选 Jaeger（一个容器，内存约 100～200 MiB，估算）还是 Tempo＋Grafana（两个容器，可和以后的看板共用）。
3. `requestId` 是否直接等于 trace id：等于更简单，但响应头的格式会变（32 位十六进制，不再是 `req_` 开头），前端和测试要跟着改；不等于则两个编号并存。我倾向**并存一个阶段**，日志同时带两者。

## 7. 代价估算
- 代码：埋点主要靠自动配置；手写部分是两处异步边界的写入／读取和 link（约 3 个类）、MDC 字段、模拟银行续上 `traceparent`。
- 测试：用 OpenTelemetry 的内存导出器（`opentelemetry-sdk-testing`，测试依赖，也要批准）断言一条 webhook 产生入站、worker、出站三段 span 且 link 正确；重建测试加排除列。
- 运行：每个请求多几个 span 对象，演示站量下可以忽略；导出失败不能影响业务（OTLP 导出是异步批量的，丢 span 不丢数据）。
- 内存：Jaeger 容器约 100～200 MiB（估算），2 GiB 机器仍够（见 [aws-plan.md](../deploy/aws-plan.md) 的内存表）。

## 8. 不做的理由（如果用户选不做）
单实例、单体、低流量；关键路径的正确性靠账本约束和对账证明，不靠追踪；P10 的结构化日志已经能按编号串起整条链。追踪是“可以做得更好”，不是缺口。
