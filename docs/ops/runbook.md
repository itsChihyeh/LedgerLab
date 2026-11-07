# LedgerLab 运行手册（P10）

指标和告警的设计见 `docs/design/observability.md`，规则在 `ops/prometheus/alerts.yml`。数据均为合成或 Stripe 测试模式。

## inbox-stuck

**告警**：`LedgerLabInboxStuck`——`ledgerlab_inbox_oldest_due_age_seconds > 300` 持续 5 分钟。含义：有已到期（`RECEIVED` 且 `next_attempt_at` 已过）的 webhook 事件，最早的一条已经等了 5 分钟以上。inbox 是所有入账的入口，它停了，支付、结算、退款事实都进不了账本。

### 怎么查（按顺序）
1. **看指标**：`ledgerlab_inbox_backlog{status="RECEIVED"}` 是否在涨；`ledgerlab_inbox_oldest_due_age_seconds` 是持续增长（worker 没在处理）还是卡在某个值附近（有事件反复失败）。值为 `NaN` 说明指标查询本身失败（`readonly` 连不上或视图无权限），先查 `metrics.query.failed` 日志，不要当作“没积压”。
2. **看日志**：搜 `event.action=inbox.failed`。每行带 `eventId`、`attempts`、`errorClass`，不带 payload。同一个 `eventId` 的 `attempts` 在涨，说明是这条事件的业务处理反复失败；没有任何 `inbox.processed` 或 `inbox.failed` 说明 worker 根本没跑。
3. **worker 是否存活**：应用进程是否在、`ledgerlab.inbox.scheduling-enabled` 是否被关掉（只有测试该关）、日志里是否有调度线程的异常。重启应用是安全的：业务事务、分录和事件状态同事务提交，未提交的会整体回滚。
4. **租约**：事件被某个 worker 抢占后 `lease_until` 之前不会被别人取。worker 崩溃后要等租约过期才会被接管（`lease_epoch` 加 1，旧 worker 迟到的写回会被拒绝）。用只读角色查 `core.v_inbox_backlog`（只有 `status`、`received_at`、`next_attempt_at` 三列）看积压量和时间；要看租约列需要运维直接连库，见下。
5. **数据库锁**：用有权限的运维账号查 `pg_stat_activity` 和 `pg_locks`，找长事务或被阻塞的 `io.inbox_event` 更新。长时间 `idle in transaction` 的会话是常见元凶；先弄清是谁的会话，再决定是否终止它。

### 能做什么
- 重启应用（worker 随之重启，过期的租约会被接管）。
- 解决根因：数据库连接、磁盘、锁、依赖对象缺失。
- 事件反复失败时按 `errorClass` 修代码或数据，再让 worker 自然重试；超过重试上限（`ledgerlab.inbox.max-attempts`，默认 5）的事件会变成 `DEAD`，只记录状态、次数和错误，由对账的老化检查开差异，按差异流程处理。

### 不能做什么
- **不手改 inbox 状态**：不 UPDATE/DELETE `io.inbox_event`、不改 `lease_*`、不把 `DEAD` 或 `PARKED` 改回 `RECEIVED`。状态变化只能由 worker 经 ADR-0007 规定的四个事务完成，并写入只追加的状态历史。
- 不直接写账本。账本只由 `ledger` 的记账入口写入；纠错走冲销加重记（ADR-0011）。
- 不伪造 webhook 重发来“绕过”卡住的事件：相同 `event_id` 同内容只会被判重复，不同内容会进 `io.inbox_conflict`。

## outbound-exhausted

**告警**：`LedgerLabOutboundExhausted`（`severity=ticket`）——`ledgerlab_outbound_exhausted{kind}` 大于 0 持续 1 分钟。含义：某类出站命令（`SUBMIT_REFUND`、`SUBMIT_PAYOUT`、`QUERY_PAYOUT`）次数用尽仍未完成，不会再自动重试（ADR-0008）。退款或提现因此卡住，占用的资金不会自动释放。不会更坏，所以是工单不是叫醒。

### 怎么查
1. **哪一类、多少条**：告警标签里的 `kind`；同时看 `ledgerlab_outbound_unfinished{kind,status}`，`UNKNOWN` 多说明回执一直拿不到。
2. **为什么失败**：搜日志 `event.action=outbound.exhausted`（命令达到上限的那一刻，带 `attempts`，结果仍是 `UNKNOWN`）和 `outbound.attempt`（每次调用的结果）；处理方或银行侧是否不可用、配置（密钥、地址）是否错误、请求本身是否被对方拒绝。`ledgerlab_outbound_attempts_total{kind,outcome,retry}` 的 `retry="true"` 速率能看出对方是否长时间不稳。
3. **该命令的业务对象**：用只读角色或界面查对应的退款／提现状态，以及对账是否已经开出 `REFUND_NOT_EXECUTED`、`PAYOUT_UNRESOLVED` 差异；按差异流程处理。

### 能做什么
- 先解决根因（处理方／银行恢复、修正配置、补缺失的数据），再按 ADR-0008 规定的方式重新发起；命令表里请求号保证同一业务对象不会被重复执行。
- 无法重新发起时，在对应的对账差异上记录处理结论。

### 不能做什么
- **不手改命令状态**：不 UPDATE/DELETE `io.outbound_command`，不把次数清零，不把状态改回 `PENDING`。
- 不直接写账本；纠错走冲销加重记（ADR-0011）。
- 不绕开命令表直接对处理方或银行下手工调用来“补发”：会失去请求号去重，可能重复退款或重复提现。

## recon-run-failed

**告警**：`LedgerLabReconRunFailed`（`severity=ticket`）——`increase(ledgerlab_recon_runs_total{outcome="failed"}[30m]) > 0`。含义：某类（`kind`：`PROCESSOR`／`BANK`／`AGING`）对账运行进入事务后失败，整笔回滚。失败的运行**不留 `recon.run` 行**，所以只有这个进程内计数和日志能看出来；告警在失败后约 30 分钟自然消失，不代表问题已解决。`ALL` 失败时三类各计一次。干跑和请求不合法（接口返回 400）不计数。

### 怎么查
1. **日志**：搜 `event.action=recon.run.failed`，带 `kind` 和 `errorClass`；不带 `runId`（已回滚）也不带异常消息（可能含 SQL）。要看完整异常需要运维到应用日志里按时间找同一线程的堆栈。
2. **常见原因**：`ConcurrencyFailureException`（与别的运行或处置并发，重试即可）；数据库连接或权限问题；规则版本与证据不匹配。
3. **确认没有半成品**：`recon.run` 里不应有该时间点的新行；有也只会是 `FINISHED` 的完整运行。

### 能做什么
- 根因解决后重新发起同一次对账（接口 `POST /api/recon/runs`，同样的 `cutoffAt` 和 `evidenceAsOf`）；运行可重复，结果由输入决定。
- 并发冲突导致的失败直接重试。

### 不能做什么
- 不手工往 `recon.run`／`recon.finding`／`recon.issue` 写行来“补”一次运行；差异的状态变化只走处置接口。
- 不修改账本去迁就对账结果；对账只读账本，账本纠错走冲销加重记（ADR-0011）。

## metrics-down

**告警**：`LedgerLabMetricsDown`（`severity=page`）——`up{job="ledgerlab-app"} == 0` 持续 5 分钟。含义：Prometheus 抓不到应用的管理端口。这时其他告警（积压、达上限、对账失败）都因为没有数据而不会触发，所以这条是它们的盲区补丁；先把它当作“可能所有告警都已失明”。

### 怎么查（按顺序）
1. **应用是否活着**：进程／容器是否在运行，是否刚重启或被 OOM；健康检查是否通过。
2. **管理端口**：`/actuator/prometheus` 是否能在应用本机访问（管理端口只绑定回环地址，从 Prometheus 所在位置能否到达取决于部署：本机用 `host.docker.internal`，Compose 里用服务名）；端口配置是否被改动。
3. **Prometheus 侧**：Targets 页面里该目标的 `lastError`（连接拒绝、超时、返回非 200）；`job` 名是否仍是 `ledgerlab-app`。
4. **抓取慢**：指标是在抓取时查库的；数据库很慢时抓取会超时。先查数据库连接和锁，再看 `metrics.query.failed` 日志。

### 能做什么
- 重启应用；修复端口或网络配置；恢复数据库。恢复后观察 `up` 变回 1，并回头检查故障期间是否积压了 inbox 事件或出站命令。

### 不能做什么
- 不为了让告警消失而改规则、改 `job` 名或延长 `for`；规则改动走代码评审。
- 不在故障期间手改库里的状态（inbox、命令、账本）；见 `inbox-stuck` 与 `outbound-exhausted` 两节的“不能做什么”。

## 其他 P10 指标：含义与异常值

| 指标 | 含义 | 什么值算异常 |
|---|---|---|
| `ledgerlab_inbox_backlog{status="RECEIVED"}` | 等待处理（含未到期重试）的 inbox 事件数 | 持续增长而不回落；与 `oldest_due_age` 同时变大基本就是 inbox-stuck |
| `ledgerlab_inbox_backlog{status="PARKED"}` | 依赖对象还没到的停放事件数 | 长时间不为 0（停放太久会触发 `EVENT_PARKED_AGED` 差异） |
| `ledgerlab_inbox_oldest_due_age_seconds` | 已到期事件里最早一条等了多久；没有则 0 | 正常运行几乎总是 0；> 300 且持续 5 分钟即告警；`NaN` 表示查询失败 |
| `ledgerlab_outbound_attempts_total{kind,outcome,retry}` | 出站调用次数；`retry="true"` 指第 2 次及以后 | `retry="true"` 的速率持续偏高：处理方或网络不稳 |
| `ledgerlab_outbound_unfinished{kind,status}` | `PENDING`／`UNKNOWN`／`IN_FLIGHT` 的出站命令数 | `UNKNOWN` 或 `IN_FLIGHT` 长时间不为 0：回执丢失或租约未过期 |
| `ledgerlab_outbound_exhausted{kind}` | 次数用尽仍未完成的命令数 | 任何大于 0 的值：自动重试已停，需要人看（资金占用不会因此释放） |
| `ledgerlab_recon_runs_total{kind,outcome}` | 非干跑的对账运行数；`outcome` 为 `finished`（已提交）或 `failed`（进入事务后失败并回滚）；启动时 6 个序列都从 0 开始，进程重启后归零 | `failed` 的增量大于 0：见 `recon-run-failed`；`finished` 长时间不增长要看是否有人在跑对账 |
| `ledgerlab_recon_issues_open{category}` | 各类别 `OPEN` 差异数 | 业务现象，不叫人；某类别突然成批出现，说明上游出了系统性问题 |
| `ledgerlab_recon_issues_oldest_open_age_seconds` | 最早一条未结差异存在多久；没有则 0 | 超过团队约定的处理时限（日间处理，不夜间告警） |
| `http_server_requests_seconds*` | Spring 自带的请求耗时，`uri` 是路由模板 | `5xx` 比例上升、`/webhooks/stripe` 耗时明显变长 |
| `ledgerlab_agent_investigations_total{status}` | 调查服务完成的调查数 | `error` 或 `budget_exhausted` 占比升高 |
| `ledgerlab_agent_investigation_duration_seconds` | 调查耗时直方图 | p95 持续接近预算上限 |
| `ledgerlab_agent_tool_calls_total` | 调查中工具调用总数 | 相对调查数突然翻倍：模型在绕圈 |

调查服务的计数在进程内，重启后归零，`rate()` 能处理这种重置。
