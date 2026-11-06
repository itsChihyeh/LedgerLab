# 通用说明（每个 P9 任务都适用）

你是 LedgerLab 的实现代理（Claude Sonnet；`AGENTS.md`“分工”一节里 Codex 的角色和规矩现在都由你承担）。当前工作目录是仓库的 P9 工作区 `C:\Users\zhiye\Documents\LedgerLab-p9`，分支 `p9`。先读 `AGENTS.md`（规则正本），再读本任务“背景”里列出的设计文档段落。设计以 `docs/` 为准。P9 的设计正本是：
- `docs/design/agent-workbench-and-mcp.md`（**P9 正本**）
- `docs/adr/0020-agent-in-workbench-and-mcp.md`，以及它所依赖的 `docs/design/investigation-agent.md`（P8）、`docs/adr/0019-…`、`docs/adr/0011-…`（纠错）、`docs/adr/0014-…`
- `docs/acceptance-criteria.md` 的 AC-11
- 已有代码：`agent/`（P8 的 Agent，`agent/README.md` 有布局表）、`frontend/src/`（工作台）、`src/main/java/dev/ledgerlab/`

## 规矩
1. 只改本任务“可改范围”里的文件（另外：为了与本任务实现保持一致而改 `docs/design/agent-workbench-and-mcp.md` 的**实现备注或措辞**一律允许；改变设计决定本身要停下来问；为适应本任务给既有测试补夹具也可以直接做，在最后输出里列出）。需要越界时停下来问。**P9 不需要 Flyway 迁移、不改主应用的生产代码**（`src/main/**`）；需要时先停下来问。
2. 不加新的运行时依赖或测试依赖（Maven、npm、Python 包都算）；Python 代码**只用标准库**，测试用 `unittest`。确需依赖时停下来问。
3. Python：类型标注；不写全局可变状态；时间、随机来源可注入（测试里固定）；`pathlib`；文本 UTF-8、换行 LF。测试方法名带 AC 编号（如 `test_ac_11_1_rejects_non_json_post`）。前端测试名、E2E 测试名也带 AC 编号（如 `ac_11_4 …`）。Java（只在任务允许时）：金额 long 整数分，测试用 Testcontainers 真库，方法名带 AC 编号。
4. **不要改 P8 的核心行为**：`agent/src/ledgerlab_agent/` 里已有模块的公开行为、`routes.json`、SOP、评测集（`agent/eval/data/**`）一律不动；任务明确允许的小的、向后兼容的补充（如导出一个已有函数）除外。改完后 `sh scripts/agent/eval.sh --check` 必须仍然退出 0 且不产生文件改动。
5. 完成前必须实际跑通（**不要跑全量 `./mvnw verify`**，全量由执行者在你结束后独立跑）：
   - 改了 Python：在仓库根目录 `python -m unittest discover -s agent/tests -t agent`，以及任务指定的其他 unittest 目录，全部通过；再跑 `sh scripts/agent/eval.sh --check`；
   - 改了 `frontend/`：`cd frontend && npm run check`；E2E 按任务说明跑；
   - 改了 Java：`./mvnw -B spotless:apply`，再 `./mvnw -B test-compile spotless:check`，再只跑相关测试类。
   最后输出里写清跑了哪些命令、多少个测试、是否全绿。
6. 跑通后在本地提交到当前分支 `p9`（英文祈使句提交信息，第一行 ≤72 字符，正文说明为什么；正文最后两行固定为 `Task: P9-<本任务编号，如 T01>` 和 `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`）。可以拆成多个提交。**不 push、不开 PR、不改 Git 配置、不用 --no-verify、不切分支。**
7. **不确定就停**：任务说明没讲清、设计文档互相冲突、或必须越出范围时，不要猜。已做的部分保持可编译、测试能跑，在最后输出里列出问题（每个问题写你倾向的选项和理由），然后结束。小的、明显的取舍可以自己定，但要写进最后输出和设计文档对应位置的“实现备注（P9-Txx）”。
8. 不读、不打印 `.env`。**不连任何外部模型 API、不连真实 Stripe、不碰云账号、不产生费用。**
9. 本机还有别的栈和别的工作区在跑（`ledgerlab-sandbox`、主工作区 `LedgerLab`、`LedgerLab-p8` 等）。**只在 `LedgerLab-p9` 里工作**；你起的 Compose 项目名必须以 `ledgerlab-agent-` 开头并带随机后缀；**只停止你自己启动的进程和项目**（按 PID 或项目名），不要按端口查杀，不要碰任何别的容器或卷。端口 8080、8090、5173 可能被别的工作区占用：E2E 之前先检查，被占用就停下来报告，不要杀别人的进程。

## 审查中定下的通用口径（不用再停下来问）
- 任务说明与设计正本冲突时，以设计正本为准照做，最后输出里写一句“任务说明第 x 条与 … 冲突，已按 … 做”。
- 设计里没写死的小细节（变量名、文件内部结构、错误短码的具体拼写），自己定，写进实现备注。

## 最后输出（中文）
- 做了什么（对应哪些 AC）、改了哪些文件、提交号
- 实际跑过的命令和结果（测试数、是否全绿）
- 自己做的取舍，以及回写了哪些设计文档
- 未完成的部分和问题（没有就写“无”）
