# P10 可观测性与真实部署准备（开工设计，2026-11-07）

本文件是 P10 的设计正本，补齐 phases.md 里 P10 的开工前置。决策见 [ADR-0021](../adr/0021-observability.md)，验收是 AC-12。

**范围（2026-11-07 定，来自 P9 收尾建议）**：① 结构化 JSON 日志，带请求号和业务编号；② 用 Actuator＋Micrometer 出指标，先做 inbox 积压、出站命令重试、未结差异数、调查服务耗时四项；③ 一条告警规则，有测试能把它触发出来；④ 按 P7 报价准备真实部署——**只写方案和报价，不执行任何云操作、不花钱**，等用户批准。

**依赖（铁律 5）**：仓库原来没有 Actuator，也没有 `micrometer-core`。②③ 需要新增 `spring-boot-starter-actuator` 和 `micrometer-registry-prometheus`（版本跟随 Spring Boot BOM，不单独写版本）以及测试用的 `prom/prometheus` 镜像（固定版本，只在测试里起）。**2026-11-07 已向用户提出，等批准**；批准前只做 ①、④ 和调查服务（Python）一侧的指标，这几项不需要新依赖。用户若选“不加依赖”，第 3、4 节改为自写只读指标端点（见第 8 节备选），其余不变。

---

## 1. 结构化日志（AC-12.1～12.3）

### 1.1 格式与开关
- 用 Spring Boot 自带的结构化日志，格式 `logstash`（扁平 JSON，MDC 的键直接成为顶层字段），不加依赖。
- 开关：`logging.structured.format.console: ${LEDGERLAB_LOG_FORMAT:}`；为空时保持现在的人读格式（本地开发、测试输出不变）。`demo` profile 和 `compose.demo.yaml`、部署草稿里设 `logstash`。如果 Spring Boot 不接受空值，改用一个 profile（`json-logs`），写进实现备注。
- 每行一个 JSON 对象，UTF-8，不跨行；异常堆栈放在 `stack_trace` 字段里。

### 1.2 关联编号（MDC 键，驼峰，固定拼写）
| 键 | 含义 | 谁放进去 |
|---|---|---|
| `requestId` | 一次 HTTP 请求 | 请求过滤器：接受客户端 `X-Request-Id`（`^[A-Za-z0-9._-]{1,64}$`，否则丢弃），没有就生成 `req_`＋ULID；响应头回写同一个值 |
| `eventId` | 处理方 webhook 事件 | 入站接收、inbox worker 处理每条事件时 |
| `commandId`、`commandKind` | 出站命令 | 出站 worker 处理每条命令时 |
| `runId` | 对账运行 `rr_` | 对账运行期间 |
| `issueId` | 对账差异 `iss_` | 开、重开、转状态、处置时 |
| `batchId` | 处理方批次 `po_` | 处理 payout 事件、批次核对、银行入账匹配时 |
| `paymentId`、`refundId`、`payoutId`、`correctionId` | 业务对象 | 能确定时 |

- MDC 必须在 `try/finally` 里清掉（worker 线程是复用的）；用一个小工具类（例如 `shared` 里的 `LogContext`，返回 `AutoCloseable`）统一放和清，不在各处手写 `MDC.put/remove`。
- `requestId` **不能**用业务的 `IdGenerator` 生成：那个生成器在测试和空库重建里带种子，多取一次就会改变后续业务编号（AC-4.13）。请求号用独立的随机来源。
- 定时任务（worker、对账、老化）没有 HTTP 请求，没有 `requestId`，只有业务编号。

### 1.3 记哪些事件
每个事件一行 INFO（失败或超限为 WARN），`message` 是固定的英文短句，另有 `event.action` 键（点分小写，固定拼写），便于检索：

| `event.action` | 时机 | 至少带的键 |
|---|---|---|
| `http.request` | 每个请求结束（访问日志；不含 `/actuator/**`） | `requestId`、`http.method`、`http.route`（路由模板，不是原始路径）、`http.status`、`durationMs` |
| `webhook.received` | 验签通过、落 inbox 后 | `eventId`、`eventType`、`duplicate` |
| `webhook.rejected` | 验签失败、livemode 等拒收 | `reason`（不带签名头、不带请求体） |
| `inbox.processed` | 一条事件处理完 | `eventId`、`eventType`、`status`、`attempts` |
| `inbox.failed` | 记失败 | `eventId`、`attempts`、`errorClass` |
| `outbound.attempt` | 每次出站调用结束 | `commandId`、`commandKind`、`attempt`、`outcome` |
| `outbound.exhausted` | 达到次数上限（现有那条 WARN 改成这种形式） | `commandId`、`commandKind`、`attempts` |
| `recon.run.finished` | 一次对账运行结束 | `runId`、`kind`、`opened`、`reopened`、`explained` |
| `recon.issue.opened`／`recon.issue.reopened`／`recon.issue.transition` | 差异开、重开、转状态 | `issueId`、`category`、`subjectType`、`subjectId`、`batchId`（主体是批次时）、`fromStatus`、`toStatus` |

### 1.4 不许出现在日志里的东西
请求体和 webhook 原文、`Stripe-Signature` 头、任何密钥或密码（`whsec_`、`sk_`、`rk_`、`pk_`、数据库密码）、银行流水 memo 原文（可能含注入文本）、操作人填写的备注全文。金额可以记（合成数据），但不是必需。测试要扫描整段捕获的日志断言这些都不出现（AC-12.3）。

---

## 2. 管理端口与暴露面（AC-12.4）
- Actuator 走**独立的管理端口** `management.server.port: ${LEDGERLAB_MANAGEMENT_PORT:8079}`，`management.server.address: 127.0.0.1`。主端口 8080 上没有 `/actuator/**`，所以演示站的公开白名单（AC-9.3）和 Caddy 都不用为它开口子；Caddy 仍显式对 `/actuator/*` 返回 404 作为第二道防线。
- 只暴露 `health`、`prometheus` 两个端点；`health` 不显示细节（`show-details: never`）。不暴露 `env`、`heapdump`、`loggers` 等。
- 测试里管理端口用 `0`（随机），避免并行测试抢端口。

## 3. 指标（AC-12.5、AC-12.6）
命名用 Micrometer 的点分名，Prometheus 输出时自动转成下划线。数据库来的数用 `readonly` 数据源查（ADR-0014），每次抓取时查；每个 gauge 的查询必须是一条有索引可用的只读 SQL，查不到库时返回 `NaN` 而不是抛异常。时间一律取注入的 `Clock`。查询放在拥有数据的模块里（ingestion、outbound、reconciliation），各自注册 meter，不跨模块读表。

| 指标 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `ledgerlab.inbox.backlog` | gauge | `status`＝`RECEIVED`／`PARKED` | 该状态的 inbox 事件数 |
| `ledgerlab.inbox.oldest.due.age` | gauge，单位秒 | — | 已到期（`RECEIVED` 且 `next_attempt_at <= now`）事件里最早 `received_at` 距今秒数；没有则 0 |
| `ledgerlab.outbound.attempts` | counter | `kind`、`outcome`、`retry`＝`true`/`false` | 每次出站调用（`retry=true` 指第 2 次及以后） |
| `ledgerlab.outbound.unfinished` | gauge | `kind`、`status`＝`PENDING`／`UNKNOWN`／`IN_FLIGHT` | 未完成的出站命令数 |
| `ledgerlab.outbound.exhausted` | gauge | `kind` | 已达次数上限仍未完成的命令数 |
| `ledgerlab.recon.issues.open` | gauge | `category` | `OPEN` 状态的差异数（只为出现过的类别出标签，类别是封闭枚举） |
| `ledgerlab.recon.issues.oldest.open.age` | gauge，单位秒 | — | 最早一条 `OPEN` 差异距今秒数；没有则 0 |
| `http.server.requests` | timer | Spring 自带 | Actuator 默认的请求耗时，`uri` 是路由模板，自然覆盖调查 Agent 读的 `/api/investigation/**` 只读接口 |

**调查服务耗时**（`agent/service`，Python 标准库）：调查服务加 `GET /agent/metrics`，输出 Prometheus 文本格式（`text/plain; version=0.0.4`）：
- `ledgerlab_agent_investigations_total{status="ok|refused|error"}` counter；
- `ledgerlab_agent_investigation_duration_seconds` histogram（桶 0.01、0.05、0.1、0.5、1、2、5、10、30、+Inf，带 `_sum`、`_count`）；
- `ledgerlab_agent_tool_calls_total` counter。
- 进程内计数，重启归零（Prometheus 的 `rate()` 能处理）；时间来源可注入，测试固定。它是 GET，不改变 AC-11.2 的“唯一的写是追加 JSONL”和“只有一个写入处理器”；同样不回 CORS 头、校验 `Host`。**不改** `agent/src/ledgerlab_agent/`（评测指纹）。

基数控制：标签值都是封闭枚举；**不许**把任何编号（`evt_`、`cmd_`、`iss_`…）当标签。

## 4. 告警（AC-12.7、AC-12.8）
- 规则文件 `ops/prometheus/alerts.yml`，一条规则：
  ```yaml
  - alert: LedgerLabInboxStuck
    expr: ledgerlab_inbox_oldest_due_age_seconds > 300
    for: 5m
    labels: {severity: page}
    annotations:
      summary: "Webhook inbox has had due events waiting over 5 minutes"
      runbook: "docs/ops/runbook.md#inbox-stuck"
  ```
  选它的理由：inbox 卡住是资金入账整体停摆的最早信号（worker 死了、数据库锁住、租约不过期），而它在正常运行时几乎永远是 0，误报少；未结差异多是业务现象，不该半夜叫人。
- 抓取配置 `ops/prometheus/prometheus.yml`（抓 app 管理端口和调查服务），供本地和部署用。
- **两层测试**：
  1. **规则单测**：`ops/prometheus/alerts.test.yml`，用 `promtool test rules` 验证：值 600 持续 6 分钟 → 告警 firing；值 600 只持续 3 分钟 → 不告警；值回到 0 → 告警消失。测试由一个 JUnit 测试在固定版本的 `prom/prometheus` 容器里执行（Testcontainers `GenericContainer`，入口改成 `promtool`），退出码 0 才算过。
  2. **从真实应用触发**：Testcontainers 起真 PostgreSQL 和应用（关掉 inbox worker 的定时调度），经真实 webhook 入口收进一条事件，把注入的时钟往前拨 10 分钟，抓 `/actuator/prometheus`，断言 `ledgerlab_inbox_oldest_due_age_seconds` ≥ 600；把这次抓到的真实值写成 `promtool` 的输入序列（`600x7` 这种形式）连同同一份 `alerts.yml` 交给容器里的 `promtool test rules`，断言告警 firing；再让 worker 处理掉事件、重新抓取，断言值回 0、同样的验证得到“不告警”。
- 告警发到哪里（Alertmanager、邮件、短信）属于部署，见第 5 节；本地只要求 Prometheus 页面上看得到 firing。
- `docs/ops/runbook.md`：这条告警出现时怎么查（看 `inbox.failed` 日志、inbox 状态、worker 是否存活、租约），以及现有其他指标各自代表什么。

## 5. 真实部署准备（AC-12.9，不执行）
- 更新 `docs/deploy/aws-plan.md`：在 P7 方案（单台 `t4g.small`＋Compose＋Caddy）上加可观测性的部分并重新核价：
  - 日志：容器 stdout JSON → Docker `json-file` 日志驱动带轮转（`max-size`、`max-file`），免费；可选 CloudWatch Logs（列出摄入和存储单价、按演示站日志量估算月费）。
  - 指标与告警：同机跑一个 Prometheus 容器（保留 7 天，内存估算），只听 127.0.0.1，用 SSM 端口转发查看；可选 Alertmanager→邮件（SES 或用户自己的 SMTP，**发信属于对外发送，必须用户决定**）。另列一个“不自建 Prometheus、改用 CloudWatch 代理抓 Prometheus 端点”的备选及价格。
  - 内存是否还够 2 GiB；不够时的退路（`t4g.medium`）和差价。
  - 价格用 `deploy/price-check.py` 查 AWS 公开价格文件并写明日期；查不到的项写“待用户用 Pricing Calculator 核价”，不编数。
- `compose.demo.yaml` 增加可选的 `observability` profile（Prometheus 容器，固定版本，挂 `ops/prometheus/`），默认不起；`deploy/Caddyfile` 对 `/actuator/*` 返回 404；`deploy/cloud-init.yaml` 设 `LEDGERLAB_LOG_FORMAT=logstash` 和 Docker 日志轮转。
- **需要用户做的事**写在文件开头：批准月费上限、开账号、决定告警通知渠道。Claude 不碰任何云账号。

## 6. 不在范围内
分布式追踪（Micrometer Tracing、OpenTelemetry 导出）、日志集中检索系统（ELK、Loki）、Grafana 面板、SLO 和错误预算、多实例部署。它们都要新基础设施或依赖，留给 P11 讨论。

## 7. 新增 AC
见 [acceptance-criteria.md](../acceptance-criteria.md) AC-12.1～AC-12.9。

## 8. 备选（用户若选“不加依赖”）
不加 Actuator：在 `investigation` 模块加只读的 `GET /api/ops/metrics`（只在管理用途下开，演示 profile 不在白名单），用 `readonly` 数据源查第 3 节的 gauge，出站重试用 `io.outbound_attempt` 表计数代替内存 counter，按 Prometheus 文本格式手写输出；告警规则和两层测试不变（`promtool` 镜像只在测试里用，不算运行时依赖，但仍要用户同意）。代价：手写格式、没有 JVM 和 HTTP 自带指标、面试时要解释为什么不用标准做法。

---

## 实现备注（P10-T01）

- **开关**：`logging.structured.format.console: ${LEDGERLAB_LOG_FORMAT:}` 在 Spring Boot 4.1 下空值可正常启动并保持人读格式（`HumanLogFormatTest` 实测），不需要 `json-logs` profile。`application-demo.yaml` 默认 `logstash`，`compose.demo.yaml` 的 app 环境也显式设了。
- **请求号过滤器**在 `shared`（`RequestLogFilter`，`@Order(HIGHEST_PRECEDENCE)`）；`DemoGateFilter` 改为 `HIGHEST_PRECEDENCE + 10`，让请求号先放入，被演示闸门拒绝的请求也有 `http.request` 行。请求号用过滤器自己持有的一个 `IdGenerator.secure(clock)` 实例生成，**不是**应用里的那个 `IdGenerator` bean，所以不会动带种子的编号序列。
- `http.route`：取不到模板，或取到的是静态资源兜底 `/**`，都写 `UNMATCHED`（`/**` 会匹配任何没有控制器认领的路径，不是真路由）。
- **出站 worker 手写 MDC**：Modulith 声明 `outbound` 不依赖任何模块（`allowedDependencies = {}`），不能调用 `shared.LogContext`，所以 `OutboundWorker` 里有一个私有的 `CommandScope`，做同样的“放入、finally 恢复进入前的值”，键名与 1.2 节相同。`outbound` 是通用的命令表，零依赖是有意的，这十几行重复可以接受，**不要**给它加 `shared` 依赖（审查已定）。
- `outbound.attempt` 的 `outcome`：有 HTTP 状态时 `HTTP_<状态码>`，否则网关给的短错误码（如 `TIMEOUT`），其余 `ERROR`；永不带正文。
- `webhook.rejected` 的 `reason`：`invalid_signature`、`invalid_envelope`（签名对但包体残缺，400）；`livemode` 事件按设计是先收进 inbox，再由 worker 拒绝，所以 `reason=livemode` 的行由 inbox worker 写，带 `eventId`。`webhook.received` 另带 `outcome`（`NEW`／`DUPLICATE`／`CONFLICT`），`duplicate` 在后两种为 `true`。
- 差异事件：对账运行内的开／重开／转状态由 `IssueStore` 写；入站管道开的差异（`JdbcIngestionIssues.open`，没有 `runId`）也写 `recon.issue.opened`，由 inbox worker 的 `eventId` 关联；入站自动收尾（付款已入账、银行退回）写 `recon.issue.transition`。`recon.run.finished` 的 `opened`／`reopened`／`explained` 由本次运行自己累计，不再查库；干跑不写这行。
- **日志在提交前写**：`recon.issue.opened` 等是在事务内、提交前写的，事务回滚时会留下一条“打开了”但库里没有的日志。演示站可以接受；不为日志引入提交后监听器，因为 ADR 要求关键写入用显式同步调用。
- **测试间的日志级别泄漏**：`ModelApplication` 和 `DemoStack` 用 `--logging.level.root=WARN` 启动应用，Logback 全 JVM 只有一个，级别留在 WARN，之后同一进程里的 `ReconLogTest`／`OutboundLogTest` 收不到 INFO 行。两者现在在 `close()` 里用 `testsupport/RootLogLevel` 恢复启动前的级别。
- **测试**：捕获日志用 `testsupport/LogEvents`（挂在根 logger 上的内存 appender，读 MDC 和 key/value，按新行唤醒等待，不 sleep）；JSON 格式本身用 `StructuredLogsTest` 的控制台捕获验证（Spring Boot 初始化日志系统之前打出的行不算，之后每行都要能解析；JVM 和 Mockito 直接写 stderr 的提示行除外）。

## 实现备注（P10-T02）
- **`status` 的实际取值是 `ok`／`budget_exhausted`／`error`，没有 `refused`**（`agent.py`、`baseline.py` 只会以 `ok` 或 `budget_exhausted` 收尾，服务自己把失败记录标成 `error`）。第 3 节写的 `ok|refused|error` 与代码不符，标签按实际枚举：三个值始终都输出（没发生过的为 0）；未知状态一律计入 `error`，标签集保持封闭。
- 耗时用服务上下文里注入的单调时钟（默认 `time.monotonic`），从开始跑调查到记录追加写完。若记录写入失败（500），也按 `error` 计一次、工具调用数记 0。409 和各类 4xx 在跑调查之前就返回，不计数。
- `GET /agent/metrics?x=1` 返回 `400 invalid_request`（`/agent/health?x=1` 仍是 404，沿用原行为）。桶上界是闭区间（`le`），`le` 的文本用 `%g` 形式（`0.01`、`1`、`30`）。
- 实例在 `InvestigationService.metrics`，模块 `metrics.py` 无全局状态。`test_static.py` 未改：新模块只用 `threading`，没有触发任何禁用模式。

## 实现备注（P10-T05）
- **未执行任何云操作**：只改文档和草稿；`price-check.py` 对 AWS 公开价格文件只读 GET（EC2、VPC、CloudWatch、SES 四个产品），结果和日期见 `aws-plan.md` “P10 可观测性”的“核价输出”。脚本现在边下载边解析（不落临时文件，不整体加载），遇到 `terms.OnDemand` 之后就停止，不读体积最大的 Reserved 部分。
- **管理端口在容器里要改监听地址**：第 2 节的 `management.server.address: 127.0.0.1` 在 Compose 里让别的容器（Prometheus）连不上。`compose.demo.yaml` 给 app 加了 `MANAGEMENT_SERVER_ADDRESS: 0.0.0.0`（环境变量覆盖配置文件）。端口 8079 不发布到主机，只在 demo 网络内可达；Caddy 只转发到 28080，且对 `/actuator/*` 返回 404。
- **Prometheus 的抓取配置不用 `ops/prometheus/prometheus.yml`**：那份抓的是本地地址，而 Compose 里的目标是服务名 `app:8079`，所以放了 `deploy/prometheus.demo.yml`，只复用 `ops/prometheus/alerts.yml`（只读挂载到 `/etc/prometheus/rules`，`create_host_path: false`，T04 未合入时 `up` 会因目录不存在而失败，但 `config` 校验通过）。
- **带 profile 的服务会让每日重置失败（实测，一个一次性 Compose 项目）**：带 `--profile observability` 起栈后，不带 profile 的 `down` 不停掉该服务，网络报 “Resource is still in use”；而 `scripts/demo/demo.py` 的 `compose()` 不带 profile，`python_env()` 还会清掉 `COMPOSE_*` 环境变量。所以 Prometheus 在 `scripts/demo` 改之前不能在部署里启用，已写入 `aws-plan.md` 和 `cloud-init.yaml` 的注释，待用户批准改 `scripts/demo`。也实测确认：未创建的外部卷不影响不带 profile 的 `up`。
- **`json-file` 日志随每日重置消失**：重置是 `down -v` 加重建容器，stdout 日志跟着容器走，只能保留不到一天。已在 `aws-plan.md` 写明，并列出两条出路（CloudWatch Logs、重置前转存），都需要用户决定。
- 设计第 5 节“内存估算”是典型值的估算，未实测，已逐项标明；Alertmanager 没有写任何配置或容器（发信由用户决定）。
- Caddyfile 用 `caddy:2` 镜像的 `caddy validate` 校验通过（`DEMO_DOMAIN` 给了假值）。
