# 通用说明（每个 P8 任务都适用）

你是 LedgerLab 的实现代理（Claude Sonnet；`AGENTS.md`“分工”一节里 Codex 的角色和规矩现在都由你承担）。当前工作目录是仓库的 P8 工作区，分支 `p8`。先读 `AGENTS.md`（规则正本），再读本任务“背景”里列出的设计文档段落。设计以 `docs/` 为准。P8 的设计正本是：
- `docs/design/investigation-agent.md`（**P8 正本**）
- `docs/adr/0019-investigation-agent-and-evaluation.md`、`docs/adr/0014-db-roles-and-threat-boundary.md`
- `docs/acceptance-criteria.md` 的 AC-10
- 现有只读接口：`src/main/java/dev/ledgerlab/investigation/`；模拟器控制接口：`simulator/README.md`；脚本风格范例：`scripts/smoke-common.sh`、`scripts/smoke-p5.sh`、`scripts/smoke/*.py`

## 规矩
1. 只改本任务“可改范围”里的文件（另外：为了与本任务实现保持一致而改 `docs/design/` 下任何文件的**实现备注或措辞**一律允许；改变设计决定本身要停下来问；为适应本任务给既有测试补夹具、改装配也可以直接做，在最后输出里列出）。需要越界时停下来问。不许改已提交的 Flyway 迁移；P8 预计不需要新迁移，需要时先停下来问。
2. 不加新的运行时依赖或测试依赖（Maven、npm、Python 包都算）；Python 代码**只用标准库**，测试用 `unittest`。确需依赖时停下来问。
3. Java：金额一律 long 整数分；“现在”来自注入的 `Clock`；测试用真实 PostgreSQL（Testcontainers），不 mock 数据库；测试方法名带 AC 编号（如 `ac_10_3_…`）。Python 测试方法名同样带 AC 编号（如 `test_ac_10_1_rejects_unknown_tool`）。
4. Python 代码放 `agent/`，包名 `ledgerlab_agent`，源码在 `agent/src/ledgerlab_agent/`，测试在 `agent/tests/`。用类型标注；不写全局可变状态；所有时间和随机来源可注入（测试里固定）。路径用 `pathlib`，文本文件 UTF-8，换行 LF。
5. 完成前必须实际跑通（**不要跑全量 `./mvnw verify`**，全量由执行者在你结束后独立跑）：
   - 改了 Python：`python -m unittest discover -s agent/tests -t agent`（在仓库根目录；如任务另有说明以任务为准），全部通过；
   - 改了 Java：先 `./mvnw -B spotless:apply`，再 `./mvnw -B test-compile spotless:check`，再只跑本任务相关测试：`./mvnw -B test -Dtest='<本任务新增或改动的测试类>,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false`；
   - 改了 `simulator/`：`./mvnw -B -f simulator/pom.xml verify`；改了 `frontend/`：`cd frontend && npm run check`。
   最后输出里写清跑了哪些测试、多少个、是否全绿。
6. 跑通后在本地提交到当前分支 `p8`（英文祈使句提交信息，第一行 ≤72 字符，正文说明为什么；正文最后两行固定为 `Task: P8-<本任务编号，如 T01>` 和 `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`）。可以拆成多个提交。**不 push、不开 PR、不改 Git 配置、不用 --no-verify、不切分支。**
7. **不确定就停**：任务说明没讲清、设计文档互相冲突、或必须越出范围时，不要猜。已做的部分保持可编译、测试能跑，在最后输出里列出问题（每个问题写你倾向的选项和理由），然后结束。小的、明显的取舍可以自己定，但要写进最后输出和设计文档的“实现备注”。
8. 不读、不打印 `.env`。P8 **不连任何外部模型 API、不连真实 Stripe、不碰云账号、不产生费用**。
9. 本机还有别的栈在跑（`ledgerlab-sandbox`、可能的演示栈和另一个工作区的测试）。你起的 Compose 项目名必须以 `ledgerlab-agent-` 开头并带随机后缀，端口用随机空闲端口或只在 Compose 网络内部通信；**只停止你自己启动的进程和项目**（按 PID 或项目名），不要按端口查杀，不要碰任何别的容器或卷。

## 审查中定下的通用口径（不用再停下来问）
- 任务说明和 ADR/数据字典冲突时，以 ADR/数据字典为准照做，最后输出里写一句“任务说明第 x 条与 … 冲突，已按 … 做”。
- readonly 一律只读视图（ADR-0014）。
- 给 investigation 增加小的、只读、向后兼容的查询方法或路由，直接做，最后输出列出；不放宽 Modulith 边界。
- 设计文档里标“待探测”“首版”的地方，按实测结果在设计文档对应位置加“实现备注（P8-Txx）”写明定稿内容，这不算改变设计决定。

## 最后输出（中文）
- 做了什么（对应哪些 AC）、改了哪些文件、提交号
- 实际跑过的命令和结果（测试数、是否全绿）
- 自己做的取舍，以及回写了哪些设计文档
- 未完成的部分和问题（没有就写“无”）
