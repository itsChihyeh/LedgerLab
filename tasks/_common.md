# 通用说明（每个 P10 任务都适用）

你是 LedgerLab 的实现代理（Claude Sonnet；`AGENTS.md`“分工”一节里 Codex 的角色和规矩现在都由你承担）。当前工作目录是仓库的 P10 工作区 `C:\Users\zhiye\Documents\LedgerLab-p10`，分支 `p10`。先读 `AGENTS.md`（规则正本），再读本任务“背景”里列出的设计文档段落。设计以 `docs/` 为准。P10 的设计正本是：
- `docs/design/observability.md`（**P10 正本**）
- `docs/adr/0021-observability.md`；相关的 `docs/adr/0014-…`（数据库角色）、`docs/adr/0007-…`（inbox）、`docs/adr/0008-…`（出站命令）、`docs/design/contracts.md` 第 2 节（编号）、`docs/design/demo-and-delivery.md`（演示站与部署）
- `docs/acceptance-criteria.md` 的 AC-12
- 已有代码：`src/main/java/dev/ledgerlab/`（Spring Boot 模块化单体）、`agent/`（Python 调查 Agent、`agent/service` 调查服务）、`deploy/`、`docs/deploy/aws-plan.md`

## 规矩
1. 只改本任务“可改范围”里的文件（另外：为了与本任务实现保持一致而改 `docs/design/observability.md` 的**实现备注或措辞**一律允许，实现备注加在文末新的“实现备注（P10-Txx）”小节；改变设计决定本身要停下来问；为适应本任务给既有测试补夹具也可以直接做，在最后输出里列出）。需要越界时停下来问。**P10 不需要 Flyway 迁移**；需要时先停下来问。
2. 不加任务没有明确允许的运行时依赖或测试依赖（Maven、npm、Python 包都算）；Python 代码**只用标准库**，测试用 `unittest`。
3. Java：金额 long 整数分；涉及数据库的测试用 Testcontainers 真 PostgreSQL，不 mock 数据库；测试方法名带 AC 编号（如 `ac_12_2_webhookLogLinesCarryTheEventId`）；时间用注入的 `Clock`，业务编号用注入的 `IdGenerator`，**请求号不得用 `IdGenerator`**（会改变带种子重建的编号序列，AC-4.13）。Spring Modulith 边界（`ModularityTests`）不得放宽。关键资金写入路径的语义不得改变：日志只是旁路，日志失败不能影响事务。
   Python：类型标注；不写全局可变状态；时间、随机来源可注入；`pathlib`；UTF-8、LF；测试名带 AC 编号（如 `test_ac_12_6_…`）。
4. **不要改 P8 的核心包** `agent/src/ledgerlab_agent/`（任何改动都会改变评测代码指纹）。`sh scripts/agent/eval.sh --check` 必须仍然退出 0 且不产生文件改动。
5. 完成前必须实际跑通（**不要跑全量 `./mvnw verify`**，全量由执行者在你结束后独立跑）：
   - 改了 Java：`./mvnw -B spotless:apply`，再 `./mvnw -B test-compile spotless:check`，再跑本任务新增和直接相关的测试类（`./mvnw -B test -Dtest=...`），以及 `ModularityTests`；
   - 改了 Python：在仓库根目录 `python -m unittest discover -s agent/tests -t agent`、`python -m unittest discover -s agent/service/tests -t agent/service`、任务指定的其他目录，全部通过；再跑 `sh scripts/agent/eval.sh --check`；
   - 改了 `frontend/`：`cd frontend && npm run check`。
   最后输出里写清跑了哪些命令、多少个测试、是否全绿。
6. 跑通后在本地提交到当前分支 `p10`（英文祈使句提交信息，第一行 ≤72 字符，正文说明为什么；正文最后两行固定为 `Task: P10-<本任务编号，如 T01>` 和 `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`）。可以拆成多个提交。**不 push、不开 PR、不改 Git 配置、不用 --no-verify、不切分支。**
7. **不确定就停**：任务说明没讲清、设计文档互相冲突、或必须越出范围时，不要猜。已做的部分保持可编译、测试能跑，在最后输出里列出问题（每个问题写你倾向的选项和理由），然后结束。小的、明显的取舍可以自己定，但要写进最后输出和设计文档的实现备注。
8. 不读、不打印 `.env`。**不连任何外部模型 API、不连真实 Stripe、不碰云账号、不产生费用。** 源码、日志和测试输出里不出现任何形似密钥的值（gitleaks 会扫所有分支）：测试里要断言“不含 `whsec_`”之类时，用拼接构造前缀字符串，不要在源码里写出完整的假密钥。
9. 本机还有别的栈和别的工作区在跑（`ledgerlab-sandbox`、主工作区 `LedgerLab`、`LedgerLab-p6`/`p8`/`p9` 等）。**只在 `LedgerLab-p10` 里工作**；你起的 Compose 项目名必须以 `ledgerlab-p10-` 开头并带随机后缀；**只停止你自己启动的进程和项目**（按 PID 或项目名），不要按端口查杀，不要碰任何别的容器或卷。5173 被一个遗留 Vite 占着，需要跑 E2E 时用 `E2E_VITE_PORT=5174`。

## 审查中定下的通用口径（不用再停下来问）
- 任务说明与设计正本冲突时，以设计正本为准照做，最后输出里写一句“任务说明第 x 条与 … 冲突，已按 … 做”。
- 设计里没写死的小细节（类名、文件内部结构、日志里 `message` 的英文措辞），自己定，写进实现备注。

## 最后输出（中文）
- 做了什么（对应哪些 AC）、改了哪些文件、提交号
- 实际跑过的命令和结果（测试数、是否全绿）
- 自己做的取舍，以及回写了哪些设计文档
- 未完成的部分和问题（没有就写“无”）
