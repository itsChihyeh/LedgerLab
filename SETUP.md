# P6 开始前检查（2026-11-02）

- 工作区：`git status` 干净；已切到 `p6`（49af25e，与 origin/p6 一致）。`origin` 是约定的仓库地址。
- 基线：按 RUNNER“P6 补充”不跑（P5 已在本机全量验证）。
- Docker：可用，Server 29.9.1。
- Codex CLI：可调用，codex-cli 0.166.0-alpha.5.1（脚本 `找工作\scripts\codex.ps1`）。
- 残留：无 `.claude\codex.lock`。`..\LedgerLab-p5-reports` worktree 已清掉（检查时干净、无未推送提交；`p5-reports` 分支保留）。
- `..\LedgerLab-p6-reports` worktree 已建，分支 `p6-reports`。
- 本机另有 Codex 桌面应用的进程在跑（10-30 启动），属于用户，未动。

## 电脑休眠设置（只查不改）

| 项 | 接通电源 | 电池 |
|---|---|---|
| 进入睡眠 | 从不 | 25 分钟 |
| 休眠 | 从不 | 从不 |

系统用的是 S0 低电量待机（连接网络），没有 S3。接通电源时不会自动睡眠。**用电池运行时 25 分钟后会睡眠**，那样 `stripe listen` 和沙箱栈的事件接收会中断（恢复后靠 catchup 补）。自动 payout 要跨天，建议全程接电源。
