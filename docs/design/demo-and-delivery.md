# P7 演示页、性能基线与交付（开工设计，2026-11-05）

本文件是 P7 的设计正本，补齐 phases.md 里 P7 的开工前置（部署、性能和技术文章的交付判据）。演示页的决定见 [ADR-0017](../adr/0017-public-break-it-demo.md)，验收见 AC-9 和本文新增的 AC-9.8～9.12。

**范围约定（用户 2026-11-05 定）**：凡是花钱或需要用户本人账号的步骤（AWS 开户/开权限、实际部署、域名），只写方案和报价，等用户确认后再做；其余全部在本地完成并可一条命令复现。

---

## 1. 运行形态

- **演示栈**：Compose 项目 `ledgerlab-demo`，profile `demo`，叠加文件 `compose.demo.yaml`。服务：postgres（数据库名 `ledgerlab_demo`）、simulator（fake Stripe＋模拟银行＋chaos relay，**不**开 sandbox profile，没有任何 Stripe 密钥）、app（Spring profile `demo`）、前端静态文件由 app 直接提供（`vite build` 的产物打进 jar 的 `static/`，不另起 Node 服务）。端口只绑 127.0.0.1（本地）；部署时前面加反向代理/负载均衡。
- **demo profile** 打开演示模块、限流、统计和每日重置；同时**关闭**运营与写接口（第 4 节）。默认 profile 和测试行为不变。
- `scripts/demo/up.sh`、`down.sh`、`reset.sh`、`status.sh`：一条命令起本地演示站（AC-9.8）。

## 2. 演示模块（新模块 `demo`，只在 demo profile 装配）

### 2.1 动作白名单（AC-9.1）

`POST /api/demo/actions/{action}`，无请求体或只有极少的枚举参数，每个动作是一段固定的场景脚本，只通过 **simulator 的公开控制接口**和**主应用已有的公开入口**驱动（不直接写库）：

| action | 做什么 | 证明什么 | 计数 |
|---|---|---|---|
| `pay` | fake Stripe 建一笔随机金额 charge（500～20000 分），webhook 经 relay 投递 | 签名入站、请款记账 | payments |
| `replay-webhook` | 对最近一条 `charge.succeeded` 调 `/sim/events/{id}/redeliver` 两次 | 重复投递被吸收 | duplicatesAbsorbed |
| `batch-first` | relay 扣住下一笔支付事件（HOLD_AND_REORDER），生成含它的 payout 并先投递 payout 事件 | 批次早于支付时停放/HELD 再自动重核 | parkedThenResolved |
| `double-refund` | 对一笔已结算支付并发两笔各 60% 的退款（同一栅栏释放） | 退款锁与额度：一笔成功受理、一笔被拒 | overRefundsRejected |
| `drop-refund-response` | fake Stripe `timeout_after_create` 一次后发退款 | 结果未知先查后发，处理方只有一笔 | unknownRecovered |
| `bank-drop-receipt` | 模拟银行“执行后丢回执”，发一笔商户提现 | 提现 UNKNOWN 不释放资金，查询后 PAID | unknownRecovered |
| `kill-worker` | 在出站命令处理中途触发受控崩溃钩子（`OutboundHooks`/`WorkerHooks`，只在 demo/test 装配），租约过期后另一个 worker 接管 | 崩溃恢复不重复执行 | workerCrashesRecovered |
| `bad-statement` | 导入一份带一行金额不符和一行未知引用的银行流水 | 对账开差异而不是改账 | discrepanciesOpened |

前置条件不满足（例如还没有已结算支付）时，动作先自动补齐前置（发支付、生成并确认 payout），页面时间线上能看到补齐步骤。每个动作返回 `{actionId, steps:[…]}`，同一动作同时只允许一个在跑（全站一把内存锁），并发点击返回 409，前端提示“上一个动作还在进行”。

### 2.2 不变量与时间线

- **运行时不变量检查**：`GET /api/audit/invariants` 扩展为 INV-1～INV-12 全部（money-model 第 5 节）。每条检查是只读 SQL，放在拥有对应数据的模块里，通过各模块公开的 `InvariantCheck` 接口汇总（ledger：1、2、3、4、5、8；payments/refunds：7、9 的 2101/2103 部分、10 的退款部分；payouts：6、9 的 2104 部分、10 的提现部分；settlement：9 的 1102 部分；reconciliation：11、12）。全部在一个 `REPEATABLE READ` 只读事务里执行。INV-6 只能检查“当前状态下”可验证的部分（2102 仅因受理退款或纠错为负、已预留提现金额 ≤ 记录的 free_cash 快照），写进实现备注。
- **SSE**：`GET /api/demo/stream` 推送 `step`（动作步骤）、`timeline`（新的 inbox 事件、分录、状态变化，按 `recorded_at` 增量轮询数据库，间隔 500ms）、`invariants`（每个动作结束后跑一次）、`counters`。断线重连用 `Last-Event-ID`。连接数上限 200，超过 503。
- AC-9.1 的“2 秒内显示”指动作完成到页面出现对应时间线条目。

### 2.3 计数与统计（AC-9.6）

- 新表 `demo.action_count(day, action, count)` 和 `demo.page_view(day, count)`，只存日期聚合；不存 IP、UA、cookie。演示页不设任何 cookie，不接第三方统计脚本。页面访问量由前端在加载时 `POST /api/demo/visit`（无参数）计一次。
- 页面上的“被吸收的重复投递”等计数来自账本和 inbox 的真实查询（不是动作计数）。

## 3. 限流（AC-9.4）

- 内存令牌桶（不加依赖）：每 IP 每分钟 20 个动作、全站每分钟 300 个动作；SSE 每 IP 最多 3 个连接。超限 429，带 `Retry-After`。
- 客户端 IP 取 `X-Forwarded-For` 的**最右一个可信代理之前**的地址：配置 `ledgerlab.demo.trusted-proxies`（CIDR 列表），默认空 = 只用连接地址。
- 被限流的请求不触发任何业务写入（测试断言分录数不变）。

## 4. 公开配置下的接口白名单（AC-9.3）

demo profile 下只开放：静态页面、`GET /api/**` 的只读查询（investigation 模块已有的）、`/api/demo/**`、`/webhooks/stripe`（只接受来自本栈 relay 的地址，配置 `ledgerlab.demo.webhook-allowed-sources`）。其余写接口（纠错、退款 API、提现 API、批次重核、对账运行、银行导入、处置差异、出站查询）一律 404（用一个按路径白名单的过滤器实现，不靠逐个控制器加注解，避免漏网）。故障钩子在 demo 只能经演示动作触发，没有直接 HTTP 入口。调查接口（P8）以后不能调用 demo 动作：demo 模块不暴露任何 Java 公开接口给其他模块（Modulith 测试断言没有模块依赖 demo）。

## 5. 每日重置（AC-9.5）

- 账本只追加、app 角色没有删除权，所以重置在应用之外做：`scripts/demo/reset.sh`（部署时由定时任务调用）先调 `GET /api/demo/summary` 把当日摘要（动作计数、不变量结果、分录/支付/退款数量）存档到 `demo-archive/<日期>.json`，然后 `docker compose down -v` 演示栈并重新 `up`，最后跑初始场景（`POST /api/demo/seed`：3 笔已结算支付、1 笔已完成退款、1 个已 BANKED 批次），确认不变量全部通过。
- 存档目录挂在演示栈之外的持久位置，重置不会删。演示页显示“上次重置时间”和最近 7 天的存档摘要。
- AC-9.5 的自动化测试用一次性 Compose 项目跑 `reset.sh` 全流程（和冒烟同一风格），不进单元测试。

## 6. 故障版本证明面板是真的（AC-9.2）

只在 test profile 存在的开关 `ledgerlab.test.break-refund-lock=true`：退款受理跳过支付行锁和额度复查。Playwright 用这个配置起后端，跑 `double-refund`，面板必须显示 INV-7 失败并指出支付编号。demo 和默认配置里这个开关不存在（类只在 test 源码集或用 `@Profile("test")`，并有测试断言 demo 上下文里没有这个 bean）。

## 7. 真实沙箱报告面板（AC-9.7）

构建时把 `docs/runs/` 里最新的 `sandbox-*.json`（P6 T08 产出，已脱敏）复制进前端静态资源；页面只读展示其中的数字和运行时间，注明“Stripe 测试模式、由本地运行产生”。还没有报告时显示“尚无运行报告”。

## 8. 性能基线（新增 AC-9.10）

- 工具：k6，用官方 Docker 镜像 `grafana/k6`（固定版本号），只在本地/CI 手动运行，不是应用依赖。脚本在 `perf/`。
- 场景（各跑 3 次取中位数，机器配置和版本写进报告）：
  - **W1 webhook 入站**：对 relay 之外直接打 `/webhooks/stripe`（签名用 fake Stripe 同一测试密钥在 k6 里算），恒定速率 50/100/200 rps 各 2 分钟，测 2xx 比例、p50/p95/p99 延迟，以及 inbox 积压排空时间。
  - **W2 单商户退款受理**：对 200 笔已结算支付并发受理退款，测吞吐和 p95；验证 ADR-0004 所说的 2102 串行瓶颈（报告实测 TPS）。
  - **W3 只读查询**：总览页与交易详情接口 50 并发。
- 跑完后不变量必须全部通过（脚本最后调 `/api/audit/invariants`），否则基线作废。
- 报告 `docs/perf/baseline-<日期>.md`：数字、环境、瓶颈分析、和多商户分片的说明。README 只引用这份报告的数字。

## 9. 部署方案与报价（新增 AC-9.11，执行需用户确认）

- `docs/deploy/aws-plan.md`：推荐单台 EC2（t4g.small，ARM）跑 `compose.demo.yaml`＋Caddy 自动 HTTPS，RDS 不用（演示数据每天重置，本机 PostgreSQL 容器即可），EventBridge Scheduler 或 cron 每日重置，CloudWatch 只开基础指标。列出按需价格的月费估算（含 EBS、流量、域名可选），以及更便宜/更稳的两个备选（Lightsail；ECS Fargate＋RDS）。价格查 AWS 官方价格页并写明查询日期。
- 提供 `deploy/` 下的 Caddyfile、cloud-init 草稿、`deploy/README.md` 的步骤，但**不执行**任何 AWS 操作。需要用户做的：开 AWS 账号或 IAM 用户、批准报价、（可选）域名。

## 10. 演示视频与技术文章（新增 AC-9.12）

- **视频**：Playwright 录制（`video: 'on'`）一条脚本化的完整演示：打开演示页 → 依次点 8 个动作 → 停在不变量面板和计数上，生成无声 webm 放 `docs/media/`（大于 20MB 就不进仓库，只写生成命令）。是否加旁白/字幕由用户决定。
- **文章草稿** `docs/articles/break-it-ledger.md`：问题（支付对账为什么难）→ 设计（只追加账本、三层幂等、结果未知不释放、独立对账）→ 证据（模型测试、真实 Stripe 运行报告、演示页、k6 基线）→ 局限（单商户、模拟银行、测试模式独立性弱）。所有数字只能引用仓库里的报告文件，每个数字旁边写出处。草稿给用户审，不对外发布。

## 11. 新增 AC

| ID | 场景 | Then | 层 |
|---|---|---|---|
| AC-9.8 | 本地一条命令起演示站 | `sh scripts/demo/up.sh` 在干净机器（只有 Docker 和 JDK）上起来，演示页可用，种子场景不变量全过；`down.sh` 清干净 | 脚本 |
| AC-9.9 | 运行时不变量 | `GET /api/audit/invariants` 覆盖 INV-1～INV-12；每条都有一个“人为破坏后能报出”的测试 | TC |
| AC-9.10 | 性能基线 | k6 三个场景有可复现的报告，跑完不变量全过 | 脚本 |
| AC-9.11 | 部署方案 | 有报价和步骤文档，用户确认前不产生任何云资源 | 文档 |
| AC-9.12 | 视频与文章 | 有自动录制的演示视频（或生成命令）和文章草稿，数字都可追到报告 | 文档 |

## 12. 与 P6 并行

P6 的 T07/T08 在等 Stripe 首笔自动 payout。P7 分支 `p7` 从 `p6` 建；P6 收尾后把 `p6` 合并进 `p7`（不 rebase），再开 P7 的 PR。演示站用 fake Stripe，不依赖 P6 的真实运行；只有报告面板要等 P6 T08 的报告文件。
