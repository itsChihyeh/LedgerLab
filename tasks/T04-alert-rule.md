# P10-T04 一条告警规则，以及把它触发出来的测试（AC-12.7、AC-12.8）

## 背景
读 `docs/design/observability.md` 第 4 节（照做）和 ADR-0021 第 3 条。依赖 T03 的 `ledgerlab_inbox_oldest_due_age_seconds`。用户已批准测试里使用固定版本的 `prom/prometheus` 镜像（只在测试里起，见“用户批准”）。用 Context7 或 Prometheus 官方文档查 `promtool test rules` 的测试文件格式（`rule_files`、`evaluation_interval`、`tests[].input_series`、`alert_rule_test[].eval_time`/`exp_alerts`），不凭记忆。镜像版本选一个当前稳定的 `prom/prometheus:vX.Y.Z`（不要 `latest`），写在一个常量里并写进实现备注。

## 要做的
1. `ops/prometheus/alerts.yml`：设计第 4 节那一条规则（组名 `ledgerlab`）。
2. `ops/prometheus/alerts.test.yml`：三个用例——超阈值持续 6 分钟 firing、只持续 3 分钟不 firing、回落后消失。
3. `ops/prometheus/prometheus.yml`：抓 app 管理端口（`host.docker.internal:8079` 或 Compose 服务名，二选一写清楚用途）和调查服务 `/agent/metrics`（8097），`rule_files` 指向 `alerts.yml`，抓取间隔 15s。
4. `docs/ops/runbook.md`：`#inbox-stuck` 一节（怎么查：`inbox.failed` 日志、`ledgerlab_inbox_backlog`、worker 是否存活、租约、数据库锁；能做什么、不能做什么——不手改 inbox 状态，按 ADR-0007 走），再用一张表说明其他 P10 指标的含义和“什么值算异常”。
5. Java 测试（放 `src/test/java/dev/ledgerlab/ops/` 或你认为合适且过 `ModularityTests` 的位置）：
   - AC-12.7：Testcontainers `GenericContainer` 起 `prom/prometheus`，把 `ops/prometheus/` 以只读方式挂进去，入口换成 `promtool`，先 `check rules alerts.yml`，再 `test rules alerts.test.yml`，断言退出码 0；另外故意喂一个错误期望（临时生成的测试文件）断言退出码非 0，证明测试真的在判。
   - AC-12.8：真实应用＋真库，inbox worker 不调度（看已有测试怎么关 `ledgerlab.inbox.scheduling-enabled`），经真实 webhook 入口（验签）收进一条事件，把可调时钟拨前 10 分钟，抓管理端口 `/actuator/prometheus` 取 `ledgerlab_inbox_oldest_due_age_seconds` 的值 v（断言 ≥ 600）；生成一份临时 `promtool` 测试文件，输入序列为“这个指标在 7 分钟里每分钟都是 v”，期望 `LedgerLabInboxStuck` firing，在容器里跑通过；然后手动调用一次 worker 处理掉事件，再抓，值为 0，生成“7 分钟都是 0、期望无告警”的测试文件，同样通过。临时文件写到 JUnit `@TempDir`，挂进容器。

## 可改范围
- `ops/**`（新目录）
- `docs/ops/runbook.md`（新文件）
- `src/test/**`
- `docs/design/observability.md`（实现备注）
- **不许碰**：`src/main/**`（如果发现必须改生产代码才能测，停下来问）、`pom.xml`、Flyway 迁移、`agent/`、`frontend/`

## 完成标准
新测试类全绿、`ModularityTests` 全绿；`spotless:check` 通过；提交带 `Task: P10-T04`。

## 用户批准
（执行者在用户批准后填写原话和日期；没有这一段不要开始。）
