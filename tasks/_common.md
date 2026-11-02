# 通用说明（每个 P5 任务都适用）

你是 LedgerLab 的实现代理（Codex）。仓库根目录就是当前工作目录，分支 `p5`。先读 `AGENTS.md`（规则正本），再读本任务“背景”里列出的设计文档段落。设计以 `docs/` 为准。P5 提现相关设计主要在：
- `docs/design/money-model.md`（提现分录、4.2 提现门槛、样例 A 第 9～11 步、样例 B、C、F、G，以及“P5 开工前置”一节）
- `docs/design/state-machines.md`（第 3 节提现状态机、第 6 节出站命令、第 7 节差异）
- `docs/design/contracts.md`（时间、编号 `mpo_`、`bank_request_id`、金额范围、API 幂等与业务编号、老化阈值 1.3、银行文件契约 4.1）
- `docs/adr/` 0004（加锁顺序）、0006（提现结果未知不释放）、0008（出站命令表）、0009（三层幂等）、0011（受限纠错，含 2026-11-02 提现补充）
- `docs/design/data-dictionary.md`（`core.payout`、`io.outbound_command`、银行表与授权契约）
- `docs/acceptance-criteria.md` 中本任务列出的 AC（AC-1.4e、AC-7、以及新增条目）
- P3 退款已实现的出站命令（`outbound` 模块、`CommandHandler`、`RefundEvidence`、API 幂等）和 P4 纠错是既有约定的范例，提现照着写。

## 规矩
1. 只改本任务“可改范围”里的文件（另外：为了与本任务实现保持一致而改 `docs/design/` 下任何文件的**实现备注或措辞**一律允许，不用停下来问；改变设计决定本身仍要问；为适应本任务的新约束而给既有测试补夹具、改装配/配置文件，也可以直接做，在最后输出里列出）；需要越界时停下来问。不许改已提交的 Flyway 迁移；新迁移按 skill `flyway-migration`，新表同时写好三个角色的授权，并在 `docs/design/data-dictionary.md` 补“实现备注”。
2. 不加新的运行时依赖（Maven 或 npm）；确需时停下来问。测试依赖同理先问。
3. 金额一律 long 整数分；精确运算（`Math.addExact` 等）。所有“现在”来自注入的 `Clock`。
4. 测试：真实 PostgreSQL（Testcontainers），不 mock 数据库；测试方法名带 AC 编号（如 `ac_5_1_…`）；并发用栅栏/受控交错，不用 sleep 碰运气。
5. 完成前必须实际跑通（**不要跑全量 `verify`**，全量测试由本机执行者在你结束后独立跑一次）：
   - 先 `./mvnw -B spotless:apply`（Windows PowerShell 用 `.\mvnw.cmd`），再 `./mvnw -B test-compile spotless:check`（编译主代码和测试代码、格式检查）；
   - 然后只跑本任务相关的测试：`./mvnw -B test -Dtest='<本任务新增或改动的测试类，逗号分隔>,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false`；改了迁移或权限，再加上已有的迁移/权限测试类；改了 jqwik 动作就加上对应的 *Properties 类；
   - 改了 `simulator/` 还要跑 `./mvnw -B -f simulator/pom.xml verify`；改了 `frontend/` 还要跑 `cd frontend && npm run check`（任务说明要求 E2E 时再跑 `npm run test:e2e`）。
   最后输出里写清跑了哪些测试类、多少个、是否全绿。执行者的全量测试红了会把失败摘要发回给你修。
6. 跑通后在本地提交到当前分支 `p5`（英文祈使句提交信息，第一行 ≤72 字符，正文说明为什么；正文最后两行固定为 `Task: <本任务编号，如 T05>` 和 `Co-Authored-By: Codex <noreply@openai.com>`，每个提交都要带）。可以拆成多个提交。**不 push、不开 PR、不改 Git 配置、不用 --no-verify。**
7. **不确定就停**：任务说明没讲清、设计文档互相冲突、或必须越出范围时，不要猜。已做的部分保持可编译，在最后输出里列出问题（每个问题写你倾向的选项和理由），然后结束。小的、明显的取舍可以自己定，但要写进最后输出和对应设计文档。
8. 不读、不写 `.env`；不碰真实 Stripe。

## 审查中定下的通用口径（不用再停下来问）
- 任务说明和 ADR/数据字典冲突时，**以 ADR/数据字典为准**照做，最后输出里写一句“任务说明第 x 条与 … 冲突，已按 … 做”。
- readonly 一律只读视图（ADR-0014）；页面需要看 `io` 的内容就加脱敏视图（只有状态、次数、时间、结果码，不含请求原文和响应体）。
- 枚举在多张表重复出现的（inbox 结果在 `io.inbox_event` 与 `io.inbox_status_history`、差异类别、hold_reason），新迁移里一起改。
- 为满足任务要求，给 `ledger`、`payments`、`settlement`、`ingestion` 的公开接口**只增加**小的、向后兼容的方法（不改已有行为和签名、不放宽 Modulith 边界），直接做，最后输出里列出。

- （P4 起）为把既有路径改成调用新增的公开入口，可以对其他模块内部类做最小调整（行为不变、原测试照过），最后输出列出。
- （P4 起）Context7 不可用时直接查对应版本官方文档，最后输出写明，不改工具配置。

## 最后输出（中文）
- 做了什么（对应哪些 AC）、改了哪些文件、提交号
- 实际跑过的命令和结果（测试数、是否全绿）
- 自己做的取舍，以及回写了哪些设计文档
- 未完成的部分和问题（没有就写“无”）
