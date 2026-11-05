# P6 本机执行说明（给在用户电脑上运行的 Claude Code 会话）

你是本机执行者：替云端的规划/审查 Claude 在 `C:\Users\zhiye\Documents\LedgerLab` 上按队列调用 Codex（模型 gpt-6-astra）实现 P6 任务，独立重跑测试，然后 push。你**不写业务代码**，不改任务说明。自主运行，不要等用户。

> **2026-11-05 起执行者换成 Claude Sonnet（用户定）**：Codex 会员已到期，不再调用 Codex。下文凡是“调 Codex”，一律改为用本机 Claude Code 命令行起一个**全新上下文**的 Sonnet 进程（架构与执行分开，换模型、换上下文）：
> `Get-Content <prompt> -Raw | claude -p --model claude-sonnet-5-5 --permission-mode acceptEdits --allowedTools "<白名单>" --output-format text *> <编号>-out.md`，工作目录为仓库根。白名单只放构建、测试和本地提交需要的命令：`Bash(./mvnw:*)`、`Bash(.\mvnw.cmd:*)`、`Bash(sh scripts/:*)`、`Bash(python:*)`、`Bash(npm:*)`、`Bash(git status:*)`、`Bash(git diff:*)`、`Bash(git log:*)`、`Bash(git add:*)`、`Bash(git commit:*)`、`Bash(docker:*)`，以及读写文件的内置工具。**不给** `git push`、不跳过权限审批。仓库的 `.claude/settings.json` 和 hooks 照常生效（会挡 `.env` 和已提交迁移）。被白名单挡住的操作让它写进最后输出的“问题”。
> 提示词、日志、输出仍放 `找工作\tmp\ledgerlab-p6\`。你（执行会话）只调度和独立验证，不写业务代码；提示词只用 `_common.md`＋任务文件（＋answers），不要塞你自己的上下文。单个任务最长 3 小时；碰到 Claude 用量上限就停在等待并写报告。

## 三个分支
- `p6`：代码分支（基于 main）。只有你 push（Codex 只在本地提交）。
- `p6-tasks`：云端 Claude 写的任务说明和队列（本文件、`QUEUE.txt`、`tasks/`）。你只读：`git fetch origin p6-tasks` 后用 `git show origin/p6-tasks:<路径>` 读，不要 checkout 它。
- `p6-reports`：你写的报告。用一个单独的 worktree（例如 `..\LedgerLab-p6-reports`，`git worktree add` 到 `p6-reports` 分支；第一次时从空的孤儿分支建）只放报告文件，不碰代码。

## 开始前（一次）
1. `git status` 必须干净，否则停下并写报告。`git fetch origin`，`git checkout p6`（本地没有就从 `origin/p6` 建），`git pull --ff-only origin p6`。
2. 确认 Docker Desktop 在运行（`docker info`）；没运行就启动它并等到可用。
3. 确认 Codex CLI 能调用：脚本 `C:\Users\zhiye\Documents\找工作\scripts\codex.ps1`。如果报找不到 `codex-windows-sandbox-setup.exe`，在本次 PowerShell 进程里把 Codex 安装目录（`C:\Users\zhiye\AppData\Local\OpenAI\Codex\bin\<哈希目录>\`，取最新的那个）加进 `$env:PATH` 再调（不要永久改 PATH）。

## 循环（每个任务）
1. `git fetch origin p6-tasks`，读 `QUEUE.txt`，找出第一个还没完成的任务（判定：`git log main..p6 --format=%B` 里没有 `Task: <编号>` 行；编号 = 文件名第一个 `-` 之前的部分，如 `T05`、`T05b`）。没有未完成任务 → 进入“等待”。
2. 拼提示词：`tasks/_common.md` ＋ 空行 ＋ `tasks/<任务文件>`，存到 `C:\Users\zhiye\Documents\找工作\tmp\ledgerlab-p6\<编号>-prompt.md`。
3. 建 `.claude\codex.lock`（已 gitignore；让 Claude 的 Spotless 钩子在 Codex 运行期间跳过）。
4. 调 Codex（后台运行并等待结束，单个任务最长给 3 小时）：
   `Get-Content <prompt> -Raw | pwsh C:\Users\zhiye\Documents\找工作\scripts\codex.ps1 exec -m gpt-6-astra -c model_reasoning_effort=high --dangerously-bypass-approvals-and-sandbox -C C:\Users\zhiye\Documents\LedgerLab -o C:\Users\zhiye\Documents\找工作\tmp\ledgerlab-p6\<编号>-out.md - *> C:\Users\zhiye\Documents\找工作\tmp\ledgerlab-p6\<编号>.log`
   （用户 2026-10-22 已同意 Codex 以完全访问模式运行；如果这个参数名在当前 CLI 版本里不同，用等价的“无沙箱、无审批”参数。）碰到额度上限：用户允许用一次重置。
5. 删除 `.claude\codex.lock`。
6. 检查：`git status` 干净（Codex 应已提交；有未提交改动就写进报告，不要替它提交）；新提交带 `Task: <编号>`；`git diff main...p6 --stat` 看改动范围有没有越出任务说明的“可改范围”（越界写进报告）。
7. **提问轮跳过测试**：Codex 的最后输出里有“问题”、`git status` 干净、而且这一轮没有任何新提交（`git rev-parse HEAD` 与调用前相同）→ 不跑任何测试，直接按步骤 8 第三种情况写报告、进入“等待”。
   否则独立重跑（全量测试**只由你跑这一次**，Codex 只跑了相关测试；不看 Codex 自述）：`.\mvnw.cmd -B verify`；本任务改了 `simulator/` 再跑 `.\mvnw.cmd -B -f simulator/pom.xml verify`；改了 `frontend/` 再跑 `cd frontend; npm run check`（任务说明要求时另跑 `npm run test:e2e`）；最后一个任务（交接）跑 `sh scripts/smoke-p5.sh`（Git Bash）。
8. 结果：
   - **全绿且 Codex 没有提出阻塞问题** → `git push -u origin p6`（网络失败按 2/4/8/16 秒重试），写报告，继续下一个任务。
   - **测试红** → 把失败摘要（失败的测试名和关键报错，≤200 行）追加到提示词末尾，标题“上一轮的独立验证失败，请修复（不要跳过或删除测试）”，再调一次 Codex（步骤 3–7）。仍红 → 不 push，写报告，进入“等待”。
   - **Codex 输出里有“问题”且说明任务没完成** → 已有的提交如果全绿可以 push（让云端审查），报告里原样附上问题，进入“等待”。
9. 报告：在 reports worktree 写 `<编号>.md`：开始/结束时间、Codex 最后输出全文（`-out.md` 的内容）、你跑的命令和结果（测试数、失败数、耗时）、越界或异常、push 的提交号。提交并 `git push origin p6-reports`。

## 等待
队列做完或被阻塞时，等回答时每 **2 分钟**、队列空时每 10 分钟 `git fetch origin p6-tasks` 看 `QUEUE.txt` 有没有新任务（云端 Claude 审查后会追加修复任务，或写 `answers/<编号>.md` 回答问题——回答出现后，把它附在该任务提示词末尾重跑这个任务）。有就回到循环。连续 8 小时没有新任务（且没有在等 payout 的任务）就结束，最后在报告分支写 `DONE.md` 总结。

## 安全
不读、不写 `.env`，不把任何密钥写进报告或日志摘要。不改 `main`，不 force push，不开 PR，不合并。不删用户文件。只在 `p6` 和 `p6-reports` 两个分支上 push。


## P6 补充（真实 Stripe 测试模式）
- 只有 `p6` 和 `p6-reports` 两个分支可以 push；两者云端已建好，本地从 `origin/` 建。`origin` 必须是 `https://github.com/itsChihyeh/LedgerLab.git`。
- **开始前**：P5 刚在这台电脑上全量验证过（1800/0），不用再跑基线。只做：`git status` 干净、`git fetch origin`、切到 `p6`、`docker info` 可用、Codex CLI 可调用、清掉 `..\LedgerLab-p5-reports` worktree（如果还在；`p5-reports` 分支已在远端，不要删分支）、建 `..\LedgerLab-p6-reports` worktree。查一下电脑休眠设置（只查不改），结果写 `SETUP.md`。
- **密钥**：Stripe 测试密钥在 `.env` 的 `STRIPE_API_KEY`。你和 Codex 都**不读、不打印** `.env`，只通过 `scripts/sandbox/env.sh`（T01 新增）把它加载进子进程环境。不要把 `stripe listen` 输出的 `whsec_`、任何 `rk_`/`sk_` 值写进报告、提交或日志摘要；报告里提到密钥只写“已加载/前缀正确/401”这类结论。
- **沙箱栈**：T04 起有一套长期运行的 `ledgerlab-sandbox` Compose 项目和后台 `stripe listen`。每个任务结束后**保持它运行**（自动 payout 要跨天），不要 `down -v`、不要删卷。电脑重启或会话被重新拉起后，先 `sh scripts/sandbox/up.sh` 再 `sh scripts/sandbox/catchup.sh`。
- **独立验证**：步骤 7 的全量测试照旧（主应用 verify；改了 simulator 再跑 simulator verify；改了前端再跑 check）。任务说明的“执行者验证”一节列出的沙箱脚本也要你亲自跑（需要 Git Bash 和本机 `.env`），结果写进报告。最后一个任务另跑 `sh scripts/smoke-p5.sh`。
- **等待 payout 的任务**：`QUEUE.txt` 里标了 `[WAIT-PAYOUT]` 的任务，调 Codex 之前先跑 `sh scripts/sandbox/catchup.sh` 和 `sh scripts/sandbox/check-payout.sh`。退出码 75（还在等 Stripe 的自动 payout）→ 不调 Codex，报告里记一行“等待 payout，<时间>”，**每 2 小时**重查一次（期间照常处理队列里后面不依赖它的任务；没有就等待）。退出码 0 → 正常调 Codex。连续 10 天仍是 75 → 写报告停在等待（新账号首次自动 payout 有 Stripe 的初始等待期，可能超过一周；2026-11-03 云端改为 10 天）。等待超过 24 小时后，每 24 小时在报告分支的 WAIT.md 追加一行状态（payout 列表条数、可用余额），不必每 2 小时都写。其他非零 → 当成失败写报告。
- **提问轮**：Codex 列出需要用户本人处理的事（例如 `stripe login`）时，原样写进报告并进入等待；不要自己替用户登录任何账号。
- **不要停下这一轮**：每个任务 push、写完报告后直接进入下一个任务或“等待”，不要发总结消息结束这一轮、也不要等用户回复。会话被停后重新拉起时，从循环第 1 步接着做。
- 2026-10-24 用户定的两条提速照旧：①Codex 只跑本任务相关测试＋编译＋Spotless，全量 `verify` 只由你跑一次；②提问轮工作区干净且无新提交就跳过测试，等回答每 2 分钟查一次。
