# P7 本机执行说明（同时收尾 P6）

你是本机执行者：在 `C:\Users\zhiye\Documents\LedgerLab` 上按队列调度实现进程，独立重跑测试，push 并写报告。**你不写业务代码、不改任务说明**。自主运行，不要等用户。

## 执行者（2026-11-05 起，用户定：架构与执行分开，换模型、换上下文）
每个任务起一个**全新上下文**的 Sonnet 进程实现：
`Get-Content <prompt> -Raw | claude -p --model claude-sonnet-5-5 --permission-mode acceptEdits --allowedTools "<白名单>" --output-format text *> <编号>-out.md`（工作目录＝仓库根；参数名在当前 CLI 版本不同就用等价参数）。
白名单只放构建、测试、本地提交需要的：`Bash(./mvnw:*)`、`Bash(.\mvnw.cmd:*)`、`Bash(sh scripts/:*)`、`Bash(python:*)`、`Bash(npm:*)`、`Bash(npx:*)`、`Bash(git status:*)`、`Bash(git diff:*)`、`Bash(git log:*)`、`Bash(git add:*)`、`Bash(git commit:*)`、`Bash(docker:*)`，以及读写文件的内置工具。**不给** `git push`，不跳过权限审批；仓库 `.claude/settings.json` 和 hooks 照常生效。提示词只用 `tasks/_common.md`＋空行＋任务文件（＋`answers/<编号>.md`），不要塞你自己的上下文。提示词、日志、输出放 `C:\Users\zhiye\Documents\找工作\tmp\ledgerlab-p7\`。单个任务最长 3 小时；碰到 Claude 用量上限就停在等待、写报告。

## 分支
- `p7`：代码（从 `p6` 建）。只有你 push。
- `p7-tasks`：任务说明、队列、回答（只读：`git fetch origin p7-tasks` 后 `git show origin/p7-tasks:<路径>`）。
- `p7-reports`：报告（单独 worktree `..\LedgerLab-p7-reports`，第一次从 `origin/p7-reports` 建）。
- P6 收尾（T07/T08）仍用 `p6`／`p6-tasks`／`p6-reports`，规则见 `origin/p6-tasks:RUNNER.md`，但执行者同样换成上面的 Sonnet 进程。

## 开始前（一次）
`git status` 干净；`git fetch origin`；`docker info` 可用；`claude --version` 可用；建 `..\LedgerLab-p7-reports` worktree；本地 `p7` 从 `origin/p7` 建。沙箱栈（ledgerlab-sandbox）已在运行，保持不动。写 `SETUP.md`。

## 循环
1. 先处理 P6 等待：距上次检查满 2 小时就跑 `sh scripts/sandbox/catchup.sh && sh scripts/sandbox/check-payout.sh`（在 `p6` 工作区状态下跑；切分支前确认工作区干净）。退出 0 → 按 P6 RUNNER 做 T07、T08（报告写 `p6-reports`），做完把 `p6` 合并进 `p7`（`git checkout p7 && git merge --no-ff p6`，冲突就停下写报告），跑全量后 push `p7`。75 → 记一行到 `p6-reports` 的 `WAIT.md`（每 24 小时一行即可）。
2. P7 队列：读 `QUEUE.txt`，找第一个未完成任务（`git log p6..p7 --format=%B` 里没有 `Task: P7-<编号>` 行）。没有 → 等待。
3. 拼提示词；建 `.claude\codex.lock`（让 Spotless 钩子在实现进程运行时跳过）；起实现进程；结束后删锁。
4. 检查 `git status` 干净、新提交带 `Task: P7-<编号>`、`git diff p6...p7 --stat` 没越出任务的可改范围。
5. 提问轮（输出里有“问题”、工作区干净、没有新提交）→ 不跑测试，写报告，等待回答（每 2 分钟查 `answers/`）。
6. 否则独立重跑：`.\mvnw.cmd -B verify`；改了 simulator 再跑 simulator verify；改了前端再跑 `cd frontend; npm run check`（任务要求时加 `npm run test:e2e`）；任务的“执行者验证”一节列出的脚本也亲自跑。
7. 全绿 → `git push -u origin p7`（网络失败按 2/4/8/16 秒重试），写报告，下一个任务。红 → 把失败摘要（≤200 行）附到提示词末尾“上一轮的独立验证失败，请修复（不要跳过或删除测试）”再起一次实现进程；仍红 → 不 push，写报告，等待。
8. 报告 `<编号>.md`：起止时间、实现进程最后输出全文、你跑的命令和结果（测试数、失败数、耗时）、越界或异常、push 的提交号。push `p7-reports`。

## 等待
等回答每 2 分钟、队列空时每 10 分钟查 `p7-tasks`；同时继续第 1 步的 P6 payout 检查。P7 队列做完且 P6 收尾完成后，在 `p7-reports` 写 `DONE.md` 并给云端发一条消息。连续 8 小时没有新任务、P6 也还在等 payout 时，写报告后结束这一轮（云端会重新拉起）。

## 安全
不读、不打印 `.env`；不把任何密钥写进报告或日志。不改 `main`，不 force push，不开 PR，不合并到 main。只 push `p6`、`p6-reports`、`p7`、`p7-reports`。不碰任何云账号（AWS 等），不花钱。每个任务 push、写完报告后直接进入下一个，不要发总结消息结束这一轮。
