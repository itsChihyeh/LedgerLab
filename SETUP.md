# P7 开始前检查（2026-11-05）

- 工作区干净。`p7` 从 `origin/p7` 建（`9266c60`）。`..\LedgerLab-p7-reports` 已建；P6 收尾用的 `..\LedgerLab-p6-reports` 也已重建。
- Docker：可用（29.9.1）。沙箱栈 `ledgerlab-sandbox` 和 `stripe listen` 在运行，没动。
- 实现进程：`claude` 2.1.292。已用一次最小调用实测 `claude -p --model claude-sonnet-5-5 --permission-mode acceptEdits --allowedTools <白名单>`：能跑，白名单内的 Bash 不被挡。没有跳过审批，没有给 push。
- 白名单内另加了 RUNNER 说的“读写文件的内置工具”：`Read,Edit,Write,Glob,Grep`。
- 电源（只查未改）：接电源时不睡眠、不休眠；用电池时 25 分钟睡眠。现在接着电源。
- P6 payout：11-05 00:36 查过，退出 75，0 条。每 2 小时在任务间隙重查，`p6-reports` 的 `WAIT.md` 每 24 小时记一行。
