# 通用说明（每个 P7 任务都适用）

你是 LedgerLab 的实现代理（Claude Sonnet；`AGENTS.md`“分工”一节里 Codex 的角色和规矩现在都由你承担）。仓库根目录就是当前工作目录，分支 `p7`。先读 `AGENTS.md`（规则正本），再读本任务“背景”里列出的设计文档段落。设计以 `docs/` 为准。P7 的设计正本是：
- `docs/design/demo-and-delivery.md`（**P7 正本**）
- `docs/adr/0017-public-break-it-demo.md`、`docs/adr/0004-concurrency-and-lock-order.md`、`docs/adr/0013-modular-monolith-and-simulators.md`、`docs/adr/0014-db-roles-and-threat-boundary.md`
- `docs/design/money-model.md` 第 5 节（INV-1～INV-12）
- `docs/acceptance-criteria.md` 的 AC-9（含新增 AC-9.8～9.12）
- `simulator/README.md`（fake Stripe、relay、模拟银行的控制接口）；`scripts/smoke-common.sh`、`scripts/smoke/*.py`、`scripts/sandbox/`（脚本风格范例）；`frontend/`（现有页面与 Playwright）

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
6. 跑通后在本地提交到当前分支 `p7`（英文祈使句提交信息，第一行 ≤72 字符，正文说明为什么；正文最后两行固定为 `Task: P7-<本任务编号，如 T05>` 和 `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`，每个提交都要带）。可以拆成多个提交。**不 push、不开 PR、不改 Git 配置、不用 --no-verify。**
7. **不确定就停**：任务说明没讲清、设计文档互相冲突、或必须越出范围时，不要猜。已做的部分保持可编译，在最后输出里列出问题（每个问题写你倾向的选项和理由），然后结束。小的、明显的取舍可以自己定，但要写进最后输出和对应设计文档。
8. 不读、不打印 `.env`。P7 不连真实 Stripe，不碰任何云账号（AWS 等），不产生费用；演示站只用 fake Stripe＋模拟银行。
9. 本机常驻的 `ledgerlab-sandbox` 栈（P6）不要停、不要删卷；P7 的演示栈用独立项目名 `ledgerlab-demo` 和不同端口（app 28080、simulator 28081、postgres 25432）。

## 审查中定下的通用口径（不用再停下来问）
- 任务说明和 ADR/数据字典冲突时，**以 ADR/数据字典为准**照做，最后输出里写一句“任务说明第 x 条与 … 冲突，已按 … 做”。
- readonly 一律只读视图（ADR-0014）；页面需要看 `io` 的内容就加脱敏视图（只有状态、次数、时间、结果码，不含请求原文和响应体）。
- 枚举在多张表重复出现的（inbox 结果在 `io.inbox_event` 与 `io.inbox_status_history`、差异类别、hold_reason），新迁移里一起改。
- 为满足任务要求，给 `ledger`、`payments`、`settlement`、`ingestion` 的公开接口**只增加**小的、向后兼容的方法（不改已有行为和签名、不放宽 Modulith 边界），直接做，最后输出里列出。

- （P4 起）为把既有路径改成调用新增的公开入口，可以对其他模块内部类做最小调整（行为不变、原测试照过），最后输出列出。
- （P4 起）Context7 不可用时直接查对应版本官方文档，最后输出写明，不改工具配置。

- （P5 加）设计文档已定下的新枚举值（纠错类型、差异类别、状态、命令 kind 等）需要加进其他模块的公开枚举或常量时，直接加，最后输出列出；这不算越界。

- （P7 加）演示相关的东西都只在 `demo` profile（或测试专用开关只在 test profile）下生效；默认配置、冒烟、CI 的行为和全部既有测试不变。
- （P7 加）演示脚本放 `scripts/demo/`，性能脚本放 `perf/`，POSIX sh＋Python 3 标准库，Git Bash 下能跑。新的外部工具只用 Docker 官方镜像且固定版本号（例如 `grafana/k6:<版本>`），不装进系统。

## 最后输出（中文）
- 做了什么（对应哪些 AC）、改了哪些文件、提交号
- 实际跑过的命令和结果（测试数、是否全绿）
- 自己做的取舍，以及回写了哪些设计文档
- 未完成的部分和问题（没有就写“无”）
