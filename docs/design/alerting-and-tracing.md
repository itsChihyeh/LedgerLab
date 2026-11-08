# P11 告警送达、补齐告警规则、追踪方案（开工设计，2026-11-07）

本文件是 P11 的设计正本。决策见 [ADR-0022](../adr/0022-alert-delivery-and-tracing-plan.md)，验收是 AC-13。建立在 P10 的 [observability.md](observability.md) 和 [ADR-0021](../adr/0021-observability.md) 之上。

**范围（2026-11-07 定）**：用户决定暂不上云，所以 P11 按 [p10.md](../handoff/p10.md) 第 7 节“不上云”的方案做：① 告警真正送达（Alertmanager→本机接收程序）；② 再加三条告警规则（出站命令达上限、对账运行失败、指标抓不到）；③ 分布式追踪**只写方案**，不加依赖、不写代码；④ 把 `p8`～`p10` 按顺序合进 `main`（PR #8、#9、#10，合并由用户点），README 阶段表更新到 P11。

**依赖（铁律 5）**：② 不需要新依赖（Micrometer 已有）。① 需要一个新镜像 `prom/alertmanager`（固定版本，只在测试和演示栈的可选 `observability` profile 里用，不进运行时依赖），**2026-11-07 已向用户提出，等批准**；接收程序只用 Python 标准库。③ 只写文档。**不对外发送任何消息**：真实通知渠道（邮件、聊天软件、推送）由用户选，选定前接收方只有本机程序。

---

## 1. 新增告警规则（AC-13.1）

都加在 `ops/prometheus/alerts.yml` 的 `ledgerlab` 组里，`LedgerLabInboxStuck` 不变。

```yaml
- alert: LedgerLabOutboundExhausted
  expr: max by (kind) (ledgerlab_outbound_exhausted) > 0
  for: 1m
  labels: {severity: ticket}
  annotations:
    summary: "Outbound {{ $labels.kind }} commands hit the attempt limit and are not finished"
    runbook: "docs/ops/runbook.md#outbound-exhausted"

- alert: LedgerLabReconRunFailed
  expr: increase(ledgerlab_recon_runs_total{outcome="failed"}[30m]) > 0
  labels: {severity: ticket}
  annotations:
    summary: "A {{ $labels.kind }} reconciliation run failed in the last 30 minutes"
    runbook: "docs/ops/runbook.md#recon-run-failed"

- alert: LedgerLabMetricsDown
  expr: up{job="ledgerlab-app"} == 0
  for: 5m
  labels: {severity: page}
  annotations:
    summary: "Prometheus cannot scrape the LedgerLab app"
    runbook: "docs/ops/runbook.md#metrics-down"
```

- **出站达上限**：命令达到次数上限后不会再自动重试，要人查（ADR-0008）；它是“退款或提现卡住”的确定信号，但不会更坏，所以是 `ticket` 不是 `page`。`for: 1m` 只为跨过一次抓取抖动。
- **对账运行失败**：失败的运行整笔回滚，库里不留 `recon.run` 行（`ReconRuns.run` 在一个 `REQUIRES_NEW` 事务里写），所以只能靠进程内计数。`increase(...[30m])` 让告警在失败后 30 分钟内保持，之后自然消失；对账可以重跑，所以是 `ticket`。
- **指标抓不到**：应用死了或管理端口不通时，其他三条规则都因为没有数据而**不会**触发，这条补上这个盲区。`job` 名在 `ops/prometheus/prometheus.yml` 和 `deploy/prometheus.demo.yml` 里都是 `ledgerlab-app`。
- 规则单测加进 `ops/prometheus/alerts.test.yml`，每条至少一个“触发”和一个“不触发”用例；对账失败再加一个“30 分钟后消失”。

### 1.1 对账运行计数（AC-13.2）
- 新 counter `ledgerlab.recon.runs`，标签 `kind`（`PROCESSOR`／`BANK`／`AGING`）、`outcome`（`finished`／`failed`），Prometheus 名 `ledgerlab_recon_runs_total`。放在 `reconciliation` 模块，`ReconRuns` 注入 `MeterRegistry`（同 `OutboundWorker` 的做法）。
- **口径**：只数**非干跑**的运行；`validate` 抛出的 `IllegalArgumentException`（请求本身不合法，接口返回 400）不算运行，不计数；进入事务以后抛出的任何异常（含 `ConcurrencyFailureException`）计 `failed`，异常照常抛出，事务语义不变；正常提交计 `finished`。
- 启动时为 3×2 个组合都注册好（初值 0），否则 `increase()` 在第一次失败时看不到起点。
- 失败时记一行 WARN：`event.action=recon.run.failed`，带 `kind`、`errorClass`，不带 `runId`（已回滚，不存在）、不带异常消息（可能含 SQL）。
- 计数和日志都是旁路：记录失败不能改变异常类型或吞掉异常。

## 2. 告警送达（AC-13.3～13.6，等镜像批准）

### 2.1 组成
```
Prometheus ──alerts──▶ Alertmanager ──webhook──▶ 接收程序（本机，127.0.0.1:9095）──▶ JSONL 文件＋一行日志
```
- `ops/alertmanager/alertmanager.yml`：一个接收方 `local-sink`（`webhook_configs`，`send_resolved: true`，`max_alerts: 20`）；路由按 `severity`：
  - `page`：`group_wait: 30s`、`group_interval: 5m`、`repeat_interval: 1h`；
  - `ticket`：`group_wait: 2m`、`group_interval: 10m`、`repeat_interval: 12h`；
  - 默认路由接 `local-sink`，`group_by: [alertname, kind]`。
- 接收地址：本地用 `http://host.docker.internal:9095/alerts`。如果所选 Alertmanager 版本的 `webhook_configs` 支持 `url_file`（以官方文档为准），**真实渠道**的地址一律走 `url_file`，文件不进仓库；本机地址可以写在配置里。
- `ops/prometheus/prometheus.yml` 和 `deploy/prometheus.demo.yml` 加 `alerting.alertmanagers`（本地 `host.docker.internal:9093`，Compose 里 `alertmanager:9093`）。
- 真实渠道以后只需要加一个接收方和一条路由；**任何对外发送都等用户选定渠道并批准**。

### 2.2 接收程序 `scripts/ops/alert-sink.py`（AC-13.5，标准库）
- `python scripts/ops/alert-sink.py --out <文件> [--host 127.0.0.1] [--port 9095]`；默认只听 127.0.0.1。
- 只接受 `POST /alerts`，`Content-Type: application/json`，体积 ≤ 64 KiB，按 Alertmanager webhook 载荷（`version: "4"`）校验必需字段；其他路径 404、其他方法 405、超大 413、坏 JSON 或缺字段 400。
- 每个通知追加一行 JSON 到 `--out`（UTF-8，LF）：收到时间（注入时钟）、`status`、`groupKey`、每条告警的 `status`／`labels`／`annotations`／`startsAt`／`endsAt`。不保存 `generatorURL`（指向内网地址）以外不需要的字段；同时在标准输出打一行 `alert <status> <alertname> <severity>`。
- 单线程处理即可（`http.server.HTTPServer`），不写全局可变状态；测试用 `unittest`，放 `scripts/ops/tests/`。

### 2.3 测试
1. **配置与路由（AC-13.3）**：在固定版本的 `prom/alertmanager` 容器里跑 `amtool check-config`，再用 `amtool config routes test` 证明 `severity=page` 和 `severity=ticket` 各自落到预期路由（按路由的 `group_wait` 等区分，或给子路由加 `matchers` 后看输出）。
2. **端到端送达（AC-13.4）**：同一个 Testcontainers 网络里起 Prometheus 和 Alertmanager；真实应用＋真库（worker 停调度），测试 JVM 里起接收端（JDK `HttpServer`），用 `Testcontainers.exposeHostPorts` 让容器访问应用管理端口和接收端。规则文件和 Alertmanager 配置从仓库正本**生成测试副本**：只允许改时间字段（规则的 `for`、`evaluation_interval`、`scrape_interval`，路由的 `group_wait`／`group_interval`／`repeat_interval`）和接收地址，测试要断言副本与正本除这些字段外相同（用 SnakeYAML 解析比较，Spring Boot 已带）。场景：收进一条事件、时钟拨前 10 分钟 → 接收端收到 `firing` 的 `LedgerLabInboxStuck`（`severity=page`，带 `summary`、`runbook`）；让 worker 处理掉 → 收到同一组的 `resolved`。等待用轮询加总超时，不 `sleep` 碰运气。
3. **不对外**：测试里不出现任何外部地址；接收端只在测试 JVM。

### 2.4 演示栈与部署草稿（AC-13.6）
- `compose.demo.yaml` 的 `observability` profile 加 `alertmanager`（同版本，挂 `ops/alertmanager/`，只发布 `127.0.0.1:9093`，无数据卷：静默规则随每日重置丢失，可以接受，写进 runbook）。
- `docs/deploy/aws-plan.md` 的内存估算加 Alertmanager 一行（估算，标明未实测），并说明真实渠道需要用户决定。
- `docs/ops/runbook.md` 加 `#outbound-exhausted`、`#recon-run-failed`、`#metrics-down` 三节，以及“怎么在本机看到告警送达”（起接收程序、起 Alertmanager）。

## 3. 追踪方案（AC-13.7，只写文档）
写在 [tracing.md](tracing.md)：用什么（Micrometer Tracing＋OpenTelemetry 桥、W3C `traceparent`）、跨异步边界怎么接（inbox 和出站命令表要存 trace 上下文，需要迁移）、和 `requestId`／业务编号的关系、对确定性重建的影响、导出到哪里（本地 Jaeger 或 Tempo，新基础设施）、需要的新依赖和镜像清单、不做的理由和做的代价。**没有实现**（项目已完成，只留方案）。

## 4. 合并与收尾（AC-13.8）
- PR #8（p8）→ #9（p9）→ #10（p10）按顺序合进 `main`；P11 结束时开 `p11` → `main` 的 PR，排在 #10 之后。
- README 阶段表补 P8～P11；`phases.md` 加 P11 一行、更新“当前状态”；交接清单 `docs/handoff/p11.md`。

## 5. 不在范围内
真实上云和真实通知渠道（等用户）；追踪的实现；Grafana 看板和 SLO（p10.md 第 7 节第 4 项，留待以后）；告警静默和值班轮换。

## 6. 新增 AC
见 [acceptance-criteria.md](../acceptance-criteria.md) AC-13.1～AC-13.8。

---

## 实现备注（P11-T01）
- **计数位置**：`ReconRuns` 构造器多一个 `MeterRegistry` 参数（既有测试的夹具已补 `SimpleMeterRegistry`）。`run` 与 `runAll` 都经过同一个私有方法 `counted`：事务提交之后每个 kind 计一次 `finished`；事务执行中（含提交时）抛出任何 `RuntimeException`／`Error` 计 `failed`，原异常原样重抛。`validate` 在 `counted` 之前，所以 400 不计数；干跑不计数（成功或失败都不计）。
- **`runAll` 失败**：三个 kind 在同一个事务里，失败时整体回滚，因此三个 kind 各计一次 `failed`、各写一行 `recon.run.failed`（`errorClass` 是同一个）。
- **日志**：WARN `recon.run.failed`，键 `kind`、`errorClass`（完整类名）；在事务之外写，所以 MDC 里没有 `runId`；计数或日志本身抛出的 `RuntimeException` 被吞掉，不替换原异常。
- **制造失败的办法（测试）**：`ReconRunMetricsTest` 用超级用户临时 `REVOKE SELECT ON recon.v_input_evidence FROM app`（运行进入事务后读的第一张表），调用后在 `finally` 里 `GRANT` 回去；异常是数据库自己的 `DataAccessException`，不改生产代码语义。
- **规则测试的时间点**：`increase(...[30m])` 的区间左开，计数器在 4 分钟升到 1 时，约 33 分钟后窗口里只剩 1，告警消失；用例取 10m／25m 触发、45m 消失，避开边界。
- 运行手册 `outbound-exhausted`、`recon-run-failed`、`metrics-down` 三节已加；“怎么在本机看到告警送达”属于 T02 之后的送达部分，本任务未写。

## 实现备注（P11-T02）
- **文件结构**：逻辑在 `scripts/ops/alert_sink.py`（可导入模块：`parse_payload`、`record_line`、`append_line`、`make_handler`、`make_server`、`main`），`scripts/ops/alert-sink.py` 只是入口。测试用 `python -m unittest discover -s scripts/ops/tests -t scripts/ops`，直接 `import alert_sink`。
- **状态码**：路径先于方法判断（非 `/alerts` 一律 404，`/alerts` 上非 POST 回 405 并带 `Allow: POST`）。`Content-Type` 不是 `application/json`、缺 `Content-Length`、非数字长度都回 400（设计只列了 404／405／413／400，没有 415／411）；声明长度 > 65536 回 413，不读正文并关闭连接。错误体统一 `{"error":"<code>"}`，成功 `{"ok":true}`。
- **校验**：`version == "4"`，`status`、每条告警的 `status` 为 `firing`／`resolved`，`groupKey`、`receiver` 为字符串，`alerts` 非空列表，每条 `labels` 为字符串映射且有字符串 `alertname`，有 `annotations` 时也必须是字符串映射。输出里每条告警只保留存在的 `status`／`labels`／`annotations`／`startsAt`／`endsAt`／`fingerprint`。
- **不校验 `Host` 头**：Alertmanager 容器发来的 `Host` 是 `host.docker.internal:9095`；安全靠默认只听 127.0.0.1。
- **时间格式**：`receivedAt` 为 UTC 毫秒精度加 `Z`，例如 `2026-11-07T08:30:15.123Z`。
- **写文件失败**回 500 `internal_error`，不打印告警行。标准输出的 `severity` 缺失时打 `unknown`。

## 实现备注（P11-T03）
- **镜像**：`prom/alertmanager:v0.28.1`（用户 2026-11-07 批准），常量 `Amtool.IMAGE`（测试）和 `compose.demo.yaml` 的 `alertmanager` 服务用同一个标签；只在测试和 `observability` profile 里用。
- **配置**：`ops/alertmanager/alertmanager.yml` 按 2.1。`url_file` 在 Alertmanager 官方文档的 `webhook_config` 里支持，本机地址直接写在配置里；`AlertmanagerConfigTest` 另有一个用例证明固定版本接受 `url_file`（真实渠道将来走它）。Prometheus 两份配置都加了 `alerting.alertmanagers`。
- **路由测试怎么区分子路由**：两条子路由接收方相同，`amtool config routes test` 的接收方列表区分不了。用 `--tree`：输出里带命中的路径，例如 `└── {severity="page"}  receiver: local-sink`，未命中子路由时只有 `default-route`。`amtool` 不打印 `group_wait` 等时间参数，所以它们用 SnakeYAML 读文件另测（`ac_13_3_thePageRoutePagesFasterThanTheTicketRoute`）。故意写坏的配置是把 `receiver: local-sink` 改成未定义的接收方，`check-config` 退出非 0。
- **端到端测试的设置**：`management.server.address=0.0.0.0`（默认的回环地址容器够不着）；`Testcontainers.exposeHostPorts(管理端口, 接收端口)`；Prometheus 与 Alertmanager 在同一 `Network`，Alertmanager 的别名是 `alertmanager`。测试副本改动的字段只有：Prometheus 的 `scrape_interval`／`evaluation_interval`（1s）、`ledgerlab-app` 的抓取目标、`alertmanagers` 目标、`LedgerLabInboxStuck` 的 `for`（5s）、Alertmanager 两条子路由的 `group_wait`／`group_interval`／`repeat_interval`（1s／2s／10m）和 webhook 地址。另一个用例 `ac_13_4_theTestCopiesDifferFromTheRealFilesOnlyInTimesAndAddresses` 把这些字段替换成标记后比较两边的解析结果，并证明改了别处（例如 `expr`）会被发现。`ledgerlab-agent` 抓取任务保持原样（目标不可达，对这条告警没有影响）。
- **等待**：每 250 ms 轮询接收端收到的列表，总超时 90 秒，超时时失败信息带两个容器的日志。接收端是测试 JVM 里的 JDK `HttpServer`（随机端口）；测试里没有外部地址。
- **耗时**：端到端用例在容器就绪后约 16 秒收到 `firing` 与 `resolved`；`AlertDeliveryTest` 两个用例共约 40 秒（含 Spring 上下文、数据库和两个容器的启动），`AlertmanagerConfigTest` 约 5 秒（每次 `amtool` 一个一次性容器）。
- **演示栈**：`alertmanager` 服务 `mem_limit: 64m`，只发布 `127.0.0.1:9093`，只读挂 `ops/alertmanager/`（`create_host_path: false`），带 `extra_hosts: host.docker.internal:host-gateway`；`prometheus` 依赖它（`service_started`）。已用假环境变量文件跑过 `docker compose --profile observability config`，**没有 `up`**。Linux 上 `host-gateway` 指向网桥地址，只听 `127.0.0.1` 的接收程序到不了，写在运行手册里，未在 Linux 上实测。
- **运行手册**：加了“在本机看到告警送达”一节；`aws-plan.md` 加了内存估算行（估算，未实测）和“需要用户决定”里的真实渠道说明。
