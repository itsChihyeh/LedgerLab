# LedgerLab 代理规则

本文件是 Claude Code、Codex 等编码代理的唯一规则正本（`CLAUDE.md` 只导入本文件）。只写看代码猜不到的事；设计细节以 `docs/` 为准，本文件与 `docs/` 冲突时以 `docs/` 为准并提出来。

## 项目一句话

我们是一家替商户收款的小型支付平台的模拟：处理方（Stripe 测试模式或 fake Stripe）通过 webhook 通知支付、结算和退款，我们用只追加的复式账本记账，并与处理方批次和模拟银行流水做独立对账。**全部数据为合成或 Stripe 测试模式，不处理真实资金。**

必读：`docs/design/money-model.md`（资金模型与手算样例）、`docs/acceptance-criteria.md`（AC，完成标准）、`docs/design/phases.md`（阶段）、`docs/adr/`（设计取舍）。

## 当前阶段

见 `docs/design/phases.md`。只做当前阶段范围内的事；发现需要越界时先提出，不要顺手做。

## 常用命令

> Maven 项目骨架建立后补全本节。在那之前没有可运行的构建。

| 用途 | 命令 |
|---|---|
| 构建＋全部测试（含 Testcontainers，需要 Docker 运行中） | `./mvnw verify`（Windows：`mvnw.cmd verify`） |
| 只跑单测 | `./mvnw test` |
| 格式化 | `./mvnw spotless:apply` |
| 本地起数据库等依赖 | `docker compose up -d` |
| 前端检查 | `cd frontend && npm run check` |

## 架构边界

- Spring Boot 模块化单体，模块：`ingestion`、`payments`、`payouts`、`ledger`、`settlement`、`reconciliation`、`investigation`。边界由 Spring Modulith `verify()` 测试强制，测试失败即违规，不得通过放宽规则来让它通过。
- `ledger` 是唯一的记账入口。其他模块只能调用它公开的记账接口，不得直接写 `ledger` schema。
- `reconciliation` 只读账本，只写差异记录。
- 模拟器（fake Stripe、模拟银行）是同仓库里单独启动的进程，有自己的 schema；主应用代码不得读模拟器的库，也不得读 `sim_truth`。
- 参考模型（随机化测试用）是独立的纯 Java 代码，**不得复用业务代码**。
- 出站命令（退款、提现）走自建的窄命令表（ADR-0008），不用 Modulith 事件登记表承担可靠投递。
- 关键资金写入（记账、额度、命令落库）用显式同步调用，不改成提交后的异步监听器。

## 铁律（违反即错）

1. 金额一律 `long`/`BIGINT` 整数分，禁止 `double`/`float`/`BigDecimal` 存金额；手续费按 `docs/design/money-model.md` 的规则取整。
2. 分录只追加、提交后封存（ADR-0003）。不写 UPDATE/DELETE 分录或 posting 的代码，纠错只走冲销加重记（ADR-0011）。
3. 只读、不可改等约束必须落在数据库角色权限和约束/触发器上，不能只在应用层检查。三个角色：`migrator`、`app`、`readonly`（ADR-0014）。
4. 已提交的 Flyway 迁移文件永不修改；改表结构就新增迁移。新表必须同时写好三个角色的授权。
5. 不新增运行时依赖、不引入新基础设施（Kafka、Redis、K8s、微服务等）前先问。
6. 不提交任何密钥、真实支付凭证或个人信息。Stripe 只接受测试模式密钥，从环境变量读取；`livemode=true` 的事件一律拒绝。
7. 仓库只放代码与公开文档，不放求职材料等个人内容。

## 测试规矩

- 涉及数据库的测试用 Testcontainers 起真实 PostgreSQL，**不 mock 数据库**。
- 权限测试要用 `app` / `readonly` 角色直连，证明越权操作确实被数据库拒绝。
- 测试方法名带 AC 编号，例如 `ac_3_2_concurrentRefundsNeverOverRefund`，便于从 AC 反查测试。
- 随机化不变量测试用 jqwik 有状态测试（ADR-0018）。每加一个业务动作，就把它加进动作集合，并更新参考模型。
- 并发测试用栅栏和受控交错，不靠 `sleep` 碰运气。
- 修 bug：先写一个能复现的失败测试，再修。
- 说“完成”之前必须实际跑过相关测试，并说明跑了什么、结果如何。

## 工作方式

- 本仓库装了 superpowers 插件（先设计、写计划、TDD、系统化调试、完成前验证）。它的流程与本文件冲突时，以本文件为准。
- 小步改动；每次改完说明对应哪条 AC、怎么验证的。
- 设计取舍写成 ADR（`.claude/skills/adr`），新增 Flyway 迁移按 `.claude/skills/flyway-migration`。
- 用到 Spring Boot 4.x、Spring Framework 7、Testcontainers、Flyway、jqwik、Stripe SDK 等库的 API 时，先用 Context7 查当前版本文档，不凭记忆。
- 改了设计层面的东西（字段、状态、契约），同步回写 `docs/design/` 对应文件。
- 提交信息用英文祈使句，第一行不超过 72 字符，说明“为什么”。

## 技术栈速查

Java 25 · Spring Boot 4.1 · Maven Wrapper · PostgreSQL · Spring JDBC（关键路径显式 SQL）· Flyway · Spring Modulith · JUnit 5 · Testcontainers · jqwik · TypeScript + React（Vite）· Playwright · Docker Compose · GitHub Actions · Python + uv（P8 调查 Agent）。
